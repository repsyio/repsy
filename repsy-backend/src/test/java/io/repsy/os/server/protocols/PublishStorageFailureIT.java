/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.server.protocols;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.exceptions.StorageUnavailableException;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.cargo.shared.crate.storage.CargoStorageService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.server.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2093: what each publish handler answers when storage fails while it writes the artifact,
 * pinned for all nine formats in one place.
 *
 * <p>The failure is the {@link StorageUnavailableException} that the storage strategy throws when
 * it cannot create, write or move an object, thrown by the storage service call that writes the
 * artifact. Since RPS-2104 {@code ProtocolErrorAdvice} answers it 503 with {@code Retry-After: 1}
 * and the message id {@code errorOccurred} on every format, in the body shape of the format's own
 * route. For each format the test asserts the status, the body shape and the header the client
 * gets, that the request left no row and no file behind (compared with what existed before the
 * request: the Docker manifest push keeps the blobs it was given) and that no usage was reported.
 *
 * <p>The table (status 503 and {@code Retry-After: 1} on every row):
 *
 * <pre>
 * Maven, npm, PyPI, Ruby, Go, Helm classic, NuGet: RestResponse body msgId=errorOccurred (NuGet
 *                                                  leaves the outage to ProtocolErrorAdvice instead of
 *                                                  its own "Publish failed")
 * Cargo:                                           {"errors":[{"detail":"errorOccurred"}]}, the
 *                                                  shape cargo prints (CargoErrorBodyAdvice)
 * Docker blob finalize, Docker manifest push:      OCI body errors[0].code=UNKNOWN (the OCI
 *                                                  specification has no code for an outage),
 *                                                  detail=errorOccurred
 * </pre>
 *
 * <p>Runs without a test transaction, because a rollback is only observable when the request's own
 * transaction rolls back for real. {@code CommittedRowsGuard} deletes the repos and users the class
 * commits. The vulnerability scan is off on every repo: a scan thread reading through a spy while a
 * stub is set up fails the test with {@code UnfinishedStubbingException} (see {@link
 * #disableSecurityScan}).
 *
 * <p>Not covered: the Helm OCI blob and manifest push (Helm's own storage service), and a failure
 * after the file was written (the {@code *PublishStorageConsistencyIT} classes cover that).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Publish when storage fails (RPS-2093)")
class PublishStorageFailureIT extends AbstractIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";
  private static final byte[] DOCKER_CONFIG =
      "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] DOCKER_LAYER = "layer".getBytes(StandardCharsets.UTF_8);

  private static final String POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
      </project>
      """;

  /** {@code @Async}, so it cannot see this class's rows; the same mock as the other publish ITs. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @MockitoSpyBean private MavenStorageService mavenStorageService;
  @MockitoSpyBean private NpmStorageService npmStorageService;
  @MockitoSpyBean private NuGetStorageService nuGetStorageService;
  @MockitoSpyBean private PypiStorageService pypiStorageService;
  @MockitoSpyBean private RubyStorageService rubyStorageService;
  @MockitoSpyBean private CargoStorageService cargoStorageService;
  @MockitoSpyBean private GoStorageService goStorageService;
  @MockitoSpyBean private HelmStorageService helmStorageService;
  @MockitoSpyBean private DockerStorageService dockerStorageService;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private PlatformTransactionManager transactionManager;

  private String token;
  private final List<UUID> createdRepoIds = new ArrayList<>();
  private List<UUID> usersBefore = List.of();

  @BeforeEach
  void rememberUsers() {
    this.usersBefore = this.jdbcTemplate.queryForList("select id from users", UUID.class);
  }

  /** {@code docker_layer} rows are not removed by the guard's repo delete, so delete here. */
  @AfterEach
  void deleteCommittedRepos() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(
        this.jdbcTemplate.queryForList("select id from users", UUID.class).stream()
            .filter(id -> !this.usersBefore.contains(id))
            .toList());
  }

  /** One publish handler: its repo type, its request, its failing seam and what it answers. */
  enum Format {
    MAVEN(RepoType.MAVEN, 503, "maven_artifact", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            put("/{repo}/com/example/lib/1.0/lib-1.0.pom", repo.getName())
                .contentType(MediaType.APPLICATION_XML)
                .content(POM));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage()).when(it.mavenStorageService).writeInputStreamToPath(any(), any(), any());
      }
    },
    NPM(RepoType.NPM, 503, "npm_package", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            put("/{repo}/{name}", repo.getName(), "failing-pkg")
                .contentType(MediaType.APPLICATION_JSON)
                .content(npmBody(repo.getName(), "failing-pkg", "1.0.0")));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) throws Exception {
        doThrow(outage())
            .when(it.npmStorageService)
            .writeTarballAndMetadata(any(), any(), any(), any(), any());
      }
    },
    NUGET(RepoType.NUGET, 503, "nuget_package", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            multipart(org.springframework.http.HttpMethod.PUT, "/{repo}/v3/package", repo.getName())
                .part(new MockPart("package", "package.nupkg", nupkg("Repsy.Failing", "1.0.0"))));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) throws Exception {
        doThrow(outage())
            .when(it.nuGetStorageService)
            .writePackage(any(), any(), any(), any(), any());
      }
    },
    PYPI(RepoType.PYPI, 503, "pypi_package", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo)
          throws Exception {
        final var content = "wheel".getBytes(StandardCharsets.UTF_8);

        // Unfiltered: the PyPI upload handler only matches a MultipartHttpServletRequest, and the
        // security filter's request wrapper hides the mock multipart request from it.
        return it.sendUnfiltered(
            multipart("/{repo}", repo.getName())
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "content",
                        "failing_pkg-1.0.0-py3-none-any.whl",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        content))
                .param(":action", "file_upload")
                .param("name", "failing-pkg")
                .param("version", "1.0.0")
                .param("filetype", "bdist_wheel")
                .param("sha256_digest", sha256Hex(content)));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) throws Exception {
        doThrow(outage()).when(it.pypiStorageService).writePackageArchive(any(), any(), any());
      }
    },
    RUBY(RepoType.RUBY, 503, "ruby_gem", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo)
          throws Exception {
        return it.send(
            post(PUBLISH_PATH, repo.getName())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem("failing-gem", "1.0.0")));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage()).when(it.rubyStorageService).writeGem(any(), any(), any(), any(), any());
      }
    },
    CARGO(RepoType.CARGO, 503, "cargo_crate", "{\"errors\":[{\"detail\":\"errorOccurred\"}]}") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            put("/{repo}/api/v1/crates/new", repo.getName())
                .content(cargoBody("failing-crate", "1.0.0")));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) throws Exception {
        doThrow(outage())
            .when(it.cargoStorageService)
            .writeCrateAndIndex(any(), any(), any(), any(), any());
      }
    },
    GO(RepoType.GOLANG, 503, "go_module", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            put("/{repo}/example.com/failing/@v/v1.0.0", repo.getName())
                .contentType("application/zip")
                .content(moduleZip("example.com/failing", "v1.0.0")));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage()).when(it.goStorageService).writeInputStreamToPath(any(), any(), any());
      }
    },
    HELM_CLASSIC(RepoType.HELM, 503, "helm_chart", "\"msgId\":\"errorOccurred\"") {
      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            multipart(UPLOAD_PATH, repo.getName())
                .part(new MockPart("chart", "chart.tgz", chart("failing", "1.0.0"))));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage()).when(it.helmStorageService).saveChart(any(), any(), any());
      }
    },
    DOCKER_BLOB(RepoType.DOCKER, 503, "docker_layer", "\"code\":\"UNKNOWN\"") {
      private String uploadId;

      @Override
      void prepare(final PublishStorageFailureIT it, final Repo repo) {
        final var start =
            it.send(post("/v2/{repo}/img/blobs/uploads/", repo.getName())).getHeader("Location");
        this.uploadId = start.substring(start.lastIndexOf('/') + 1);
      }

      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo) {
        return it.send(
            put("/v2/{repo}/img/blobs/uploads/{id}", repo.getName(), this.uploadId)
                .param("digest", sha256(DOCKER_LAYER))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(DOCKER_LAYER));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage())
            .when(it.dockerStorageService)
            .appendInputStreamToPath(any(), any(), any());
      }
    },
    DOCKER_MANIFEST(RepoType.DOCKER, 503, "docker_image", "\"code\":\"UNKNOWN\"") {
      @Override
      void prepare(final PublishStorageFailureIT it, final Repo repo) throws Exception {
        it.pushBlob(repo, DOCKER_CONFIG);
        it.pushBlob(repo, DOCKER_LAYER);
      }

      @Override
      MockHttpServletResponse publish(final PublishStorageFailureIT it, final Repo repo)
          throws Exception {
        return it.sendUnfiltered(
            put("/v2/{repo}/img/manifests/v1", repo.getName())
                .contentType(OCI_MANIFEST)
                .content(dockerManifest()));
      }

      @Override
      void failStorage(final PublishStorageFailureIT it) {
        doThrow(outage()).when(it.dockerStorageService).writeInputStreamToPath(any(), any(), any());
      }
    };

    final RepoType type;
    final int status;
    final String table;
    final String bodyMarker;

    Format(final RepoType type, final int status, final String table, final String bodyMarker) {
      this.type = type;
      this.status = status;
      this.table = table;
      this.bodyMarker = bodyMarker;
    }

    /** Anything the request needs before the storage is made to fail. */
    void prepare(final PublishStorageFailureIT it, final Repo repo) throws Exception {}

    abstract MockHttpServletResponse publish(PublishStorageFailureIT it, Repo repo)
        throws Exception;

    abstract void failStorage(PublishStorageFailureIT it) throws Exception;
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Format.class)
  @DisplayName("a storage failure while writing the artifact answers 503 in the format's own body")
  void storageFailureLeavesNothingBehind(final Format format) throws Exception {
    // Both create rows through helpers that flush, which needs a transaction; each commits here.
    final var inTransaction = new TransactionTemplate(this.transactionManager);
    this.token = inTransaction.execute(status -> this.adminProtocolBearerToken());
    final var repo =
        inTransaction.execute(status -> this.seedRepo(format.type, uniqueRepoName("pub-fail")));
    this.disableSecurityScan(repo.getId());
    format.prepare(this, repo);
    this.createdRepoIds.add(repo.getId());
    final var rowsBefore = this.rows(format, repo);
    final var filesBefore = storedFiles(repo);
    clearInvocations(this.usageUpdateService);
    format.failStorage(this);

    final var response = format.publish(this, repo);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(format.status);
    assertThat(response.getContentAsString()).contains(format.bodyMarker);
    assertThat(response.getContentAsString()).contains("errorOccurred");
    if (!format.bodyMarker.contains("msgId")) {
      assertThat(response.getContentAsString())
          .as("a %s client does not read the RestResponse envelope", format)
          .doesNotContain("\"msgId\"");
    }
    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(this.rows(format, repo)).as("%s rows", format.table).isEqualTo(rowsBefore);
    assertThat(storedFiles(repo)).as("files of %s", repo.getName()).isEqualTo(filesBefore);
    verifyNoInteractions(this.usageUpdateService);
  }

  /** What the storage strategy throws when it cannot write (RPS-2104). */
  private static StorageUnavailableException outage() {
    return new StorageUnavailableException(
        "The storage could not be written", new IOException("storage went away"));
  }

  private MockHttpServletResponse send(final AbstractMockHttpServletRequestBuilder<?> request) {
    try {
      return this.mockMvc
          .perform(request.header(AUTHORIZATION, this.token).with(protocolPort()))
          .andReturn()
          .getResponse();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Sent through a {@code MockMvc} without the servlet filters: the manifest handler matches the
   * {@code Content-Type} exactly, and the character-encoding filter would append {@code
   * ;charset=UTF-8} to it (see {@code DockerWire}).
   */
  private MockHttpServletResponse sendUnfiltered(
      final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(request.header(AUTHORIZATION, this.token).with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private long rows(final Format format, final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from " + format.table + " where repo_id = ?",
            Long.class,
            repo.getId());

    return count == null ? 0 : count;
  }

  private static List<Path> storedFiles(final Repo repo) {
    final var dir = storageDirOf(repo);

    if (!Files.exists(dir)) {
      return List.of();
    }

    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code HEAD}, start, finalize: the sequence of {@code docker push} for one blob. */
  private void pushBlob(final Repo repo, final byte[] blob) throws Exception {
    final var digest = sha256(blob);
    final var start = this.send(post("/v2/{repo}/img/blobs/uploads/", repo.getName()));
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.send(
            put("/v2/{repo}/img/blobs/uploads/{id}", repo.getName(), uploadId)
                .param("digest", digest)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(blob));
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
    this.send(head("/v2/{repo}/img/blobs/{digest}", repo.getName(), digest));
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures, copied from the per-format ITs (their builders are private)
  // ---------------------------------------------------------------------------------------------

  private static String sha256(final byte[] bytes) {
    return "sha256:" + sha256Hex(bytes);
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String dockerManifest() {
    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
        .formatted(
            OCI_MANIFEST,
            OCI_CONFIG,
            sha256(DOCKER_CONFIG),
            DOCKER_CONFIG.length,
            OCI_LAYER,
            sha256(DOCKER_LAYER),
            DOCKER_LAYER.length);
  }

  private static byte[] npmBody(final String repoName, final String name, final String version) {
    final var tarball = "tarball".getBytes(StandardCharsets.UTF_8);
    final var dist = new LinkedHashMap<String, Object>();
    dist.put(
        "tarball",
        "http://localhost:9090/" + repoName + "/" + name + "/-/" + name + "-" + version + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", name);
    versionMetadata.put("version", version);
    versionMetadata.put("description", "storage failure fixture");
    versionMetadata.put("dist", dist);

    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", name);
    body.put("name", name);
    body.put("dist-tags", Map.of("latest", version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            name + "-" + version + ".tgz",
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarball),
                "length",
                tarball.length)));

    return MAPPER.writeValueAsBytes(body);
  }

  private static byte[] nupkg(final String id, final String version) {
    final var nuspec =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
          <metadata>
            <id>%s</id>
            <version>%s</version>
            <authors>Repsy</authors>
            <description>storage failure fixture</description>
          </metadata>
        </package>
        """
            .formatted(id, version);
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(id + ".nuspec"));
      zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("lib/net8.0/" + id + ".dll"));
      zip.write("dll".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static byte[] moduleZip(final String modulePath, final String version) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = modulePath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + modulePath + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "marker.go"));
      zip.write("package marker\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private static byte[] cargoBody(final String name, final String version) {
    final var manifest =
        "[package]\nname = \"%s\"\n".formatted(name).getBytes(StandardCharsets.UTF_8);
    final var crate = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(crate))) {
      final var entry = new TarArchiveEntry(name + "-" + version + "/Cargo.toml");

      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    final var metadata =
        MAPPER
            .writeValueAsString(
                Map.of(
                    "name",
                    name,
                    "vers",
                    version,
                    "deps",
                    List.of(),
                    "features",
                    Map.of(),
                    "authors",
                    List.of(),
                    "description",
                    "storage failure fixture",
                    "license",
                    "MIT"))
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);
    out.writeBytes(u32le(crate.size()));
    out.writeBytes(crate.toByteArray());

    return out.toByteArray();
  }
}
