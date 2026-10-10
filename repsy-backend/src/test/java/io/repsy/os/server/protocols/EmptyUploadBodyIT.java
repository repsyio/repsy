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

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1466: what each upload path does with a body that has no byte in it, and with a body a client
 * sent as {@code application/x-www-form-urlencoded} ({@code curl --data-binary} does), the
 * follow-up of RPS-1443, which made Maven answer {@code 400 mavenUploadBodyEmpty}.
 *
 * <p>The rule: a body with no byte in it is refused with a 400 and a message id of its own where no
 * valid artifact can be empty (a Go module zip, a Cargo publish body and its crate, a Helm chart,
 * an npm publish document, a manifest), and it is <em>accepted</em> where the protocol allows it:
 * the empty blob ({@code sha256:e3b0c442...}, and the 2-byte {@code {}} config) is a valid OCI
 * blob, so a Docker or Helm OCI blob upload of no byte is stored, and a chunk of no byte is
 * acknowledged. Nothing is stored for a refused body and the usage is not touched.
 *
 * <p>MockMvc cannot reproduce Tomcat's own parsing of a form-typed {@code POST} body, so that the
 * Ruby gem push (a {@code POST}) keeps its body is proven by the e2e suite against a real server;
 * the Ruby case here pins what the handler does with a form-typed body and an empty one.
 */
@DisplayName("Empty and form-typed upload bodies (RPS-1466)")
class EmptyUploadBodyIT extends AbstractIT {

  private static final String FORM = "application/x-www-form-urlencoded";
  private static final String OCTET = "application/octet-stream";
  private static final String EMPTY_BLOB =
      "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
  private static final String DOCKER_MANIFEST =
      "application/vnd.docker.distribution.manifest.v2+json";

  /**
   * {@code @Async}, so it cannot see this class's uncommitted rows; the same mock as the others.
   */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @org.springframework.beans.factory.annotation.Autowired
  private WebApplicationContext webApplicationContext;

  private ResultActions send(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(
        request.header(AUTHORIZATION, this.adminProtocolBearerToken()).with(protocolPort()));
  }

  /**
   * Sent through a {@code MockMvc} without the servlet filters: the manifest handlers match the
   * {@code Content-Type} exactly, and the character-encoding filter would append {@code
   * ;charset=UTF-8} to it, which a real client never sends (see {@code DockerWire}).
   */
  private ResultActions sendUnfiltered(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            request.header(AUTHORIZATION, this.adminProtocolBearerToken()).with(protocolPort()));
  }

  private static List<Path> storedFiles(final Repo repo) {
    final var dir = storageDirOf(repo);

    if (!Files.exists(dir)) {
      return List.of();
    }

    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertNothingStored(final Repo repo) {
    assertThat(storedFiles(repo)).as("files stored for %s", repo.getName()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Go
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {"application/zip", FORM})
  @DisplayName("Go: refuses a module zip with no byte in it with 400 goModuleZipEmpty")
  void goRefusesAnEmptyZip(final String contentType) throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("go-empty"));

    expectError(
        this.send(
            put("/{repo}/example.com/empty/@v/v1.0.0", repo.getName())
                .contentType(contentType)
                .content(new byte[0])),
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "goModuleZipEmpty",
        "goModuleZipEmpty",
        "The request body is empty: a module zip must have at least one byte.");

    assertNothingStored(repo);
    verifyNoInteractions(this.usageUpdateService);
  }

  // ---------------------------------------------------------------------------------------------
  // Cargo
  // ---------------------------------------------------------------------------------------------

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private static byte[] cargoBody(final byte[] metadata, final Integer crateLength) {
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);

    if (crateLength != null) {
      out.writeBytes(u32le(crateLength));
    }

    return out.toByteArray();
  }

  private static final byte[] CARGO_METADATA =
      ("{\"name\":\"emptycrate\",\"vers\":\"1.0.0\",\"deps\":[],\"features\":{},\"authors\":[],"
              + "\"description\":\"RPS-1466\",\"license\":\"MIT\"}")
          .getBytes(StandardCharsets.UTF_8);

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "empty body|the publish body is empty",
        "two bytes|the publish body ends before a length field is complete",
        "zero-length metadata|the crate's metadata JSON is empty",
        "no crate length|the publish body ends before a length field is complete",
        "zero-length crate|the crate is empty"
      })
  @DisplayName("Cargo: refuses a publish body with no crate in it with a cargo-shaped 400")
  void cargoRefusesAPublishWithoutACrate(final String caseAndDetail) throws Exception {
    final var parts = caseAndDetail.split("\\|");
    final var body =
        switch (parts[0]) {
          case "empty body" -> new byte[0];
          case "two bytes" -> new byte[] {1, 0};
          case "zero-length metadata" -> u32le(0);
          case "no crate length" -> cargoBody(CARGO_METADATA, null);
          case "zero-length crate" -> cargoBody(CARGO_METADATA, 0);
          case null, default -> throw new IllegalArgumentException(parts[0]);
        };
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo-empty"));

    for (final var contentType : List.of(OCTET, FORM)) {
      this.send(
              put("/{repo}/api/v1/crates/new", repo.getName())
                  .contentType(contentType)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].detail").value(parts[1]));
    }

    assertNothingStored(repo);
    verifyNoInteractions(this.usageUpdateService);
  }

  // ---------------------------------------------------------------------------------------------
  // npm
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {"application/json", OCTET, FORM})
  @DisplayName("npm: refuses a publish with no byte in its body with 400 npmPublishBodyEmpty")
  void npmRefusesAnEmptyPublish(final String contentType) throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm-empty"));

    expectError(
        this.send(
            put("/{repo}/empty-pkg", repo.getName()).contentType(contentType).content(new byte[0])),
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "npmPublishBodyEmpty",
        "npmPublishBodyEmpty",
        "The request body is empty: a publish must send the package document.");

    assertNothingStored(repo);
    verifyNoInteractions(this.usageUpdateService);
  }

  // ---------------------------------------------------------------------------------------------
  // Ruby
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {OCTET, FORM})
  @DisplayName("Ruby: a gem pushed as a form-typed POST is stored byte for byte")
  void rubyStoresAFormTypedGem(final String contentType) throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-form"));
    final var gem = gem("form-typed-gem", "1.0.0");

    this.send(post(PUBLISH_PATH, repo.getName()).contentType(contentType).content(gem))
        .andExpect(status().isOk());

    this.send(get("/{repo}/gems/form-typed-gem-1.0.0.gem", repo.getName()))
        .andExpect(status().isOk())
        .andExpect(content().bytes(gem));
  }

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {OCTET, FORM})
  @DisplayName("Ruby: a push with no byte in its body is refused with 400 invalidGemFile")
  void rubyRefusesAnEmptyGem(final String contentType) throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-empty"));

    expectError(
        this.send(post(PUBLISH_PATH, repo.getName()).contentType(contentType).content(new byte[0])),
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalidGemFile",
        "invalidGemFile",
        "Invalid gem file.");

    assertNothingStored(repo);
    verifyNoInteractions(this.usageUpdateService);
  }

  // ---------------------------------------------------------------------------------------------
  // Docker and Helm OCI
  // ---------------------------------------------------------------------------------------------

  private String startUpload(final Repo repo) throws Exception {
    final var location =
        this.send(post("/v2/{repo}/img/blobs/uploads/", repo.getName()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    return location.substring(location.lastIndexOf('/') + 1);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"DOCKER", "HELM"})
  @DisplayName("OCI: the empty blob is a valid blob: a chunk and a finalize of no byte are stored")
  void ociAcceptsTheEmptyBlob(final String type) throws Exception {
    final var repo = this.seedRepo(RepoType.valueOf(type), uniqueRepoName("oci-blob"));
    final var uploadId = this.startUpload(repo);

    this.send(
            patch("/v2/{repo}/img/blobs/uploads/{id}", repo.getName(), uploadId)
                .contentType(FORM)
                .content(new byte[0]))
        .andExpect(status().isAccepted());

    this.send(
            put("/v2/{repo}/img/blobs/uploads/{id}", repo.getName(), uploadId)
                .param("digest", EMPTY_BLOB)
                .contentType(FORM)
                .content(new byte[0]))
        .andExpect(status().isCreated());

    // HEAD, which sees the layer row this class's test transaction has not committed: a Docker blob
    // GET runs outside any transaction.
    this.send(head("/v2/{repo}/img/blobs/{digest}", repo.getName(), EMPTY_BLOB))
        .andExpect(status().isOk())
        .andExpect(header().string("Docker-Content-Digest", EMPTY_BLOB))
        .andExpect(header().string("Content-Length", "0"));

    assertThat(storedFiles(repo))
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getFileName().toString()).isEqualTo(EMPTY_BLOB);
              assertThat(file).isEmptyFile();
            });
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"DOCKER", "HELM"})
  @DisplayName("OCI: a finalize with no byte still has its digest checked against the upload")
  void ociChecksTheDigestOfAnEmptyFinalize(final String type) throws Exception {
    final var repo = this.seedRepo(RepoType.valueOf(type), uniqueRepoName("oci-digest"));
    final var uploadId = this.startUpload(repo);

    this.send(
            put("/v2/{repo}/img/blobs/uploads/{id}", repo.getName(), uploadId)
                .param("digest", "sha256:" + "a".repeat(64))
                .contentType(OCTET)
                .content(new byte[0]))
        .andExpect(status().is4xxClientError());

    assertThat(storedFiles(repo)).noneMatch(file -> file.toString().contains("aaaaaaaa"));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @ValueSource(
      strings = {
        "DOCKER|" + OCI_MANIFEST + "|v1",
        "DOCKER|" + OCI_INDEX + "|v1",
        "DOCKER|" + DOCKER_MANIFEST + "|v1",
        "DOCKER|" + OCI_MANIFEST + "|" + EMPTY_BLOB,
        "HELM|" + OCI_MANIFEST + "|1.0.0",
        "HELM|" + OCI_INDEX + "|1.0.0",
        "HELM|" + OCI_MANIFEST + "|" + EMPTY_BLOB
      })
  @DisplayName("OCI: refuses a manifest with no byte in it with 400 manifestInvalidJson")
  void ociRefusesAnEmptyManifest(final String typeMediaTypeAndReference) throws Exception {
    final var parts = typeMediaTypeAndReference.split("\\|");
    final var repo = this.seedRepo(RepoType.valueOf(parts[0]), uniqueRepoName("oci-manifest"));

    this.sendUnfiltered(
            put("/v2/{repo}/img/manifests/{reference}", repo.getName(), parts[2])
                .contentType(parts[1])
                .content(new byte[0]))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_INVALID"))
        .andExpect(jsonPath("$.errors[0].detail").value("manifestInvalidJson"));

    assertThat(storedFiles(repo)).noneMatch(file -> file.toString().contains("manifests"));
    verifyNoInteractions(this.usageUpdateService);
  }

  // ---------------------------------------------------------------------------------------------
  // Helm classic
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Helm classic: refuses a chart part with no byte in it with 400 helmChartEmpty")
  void helmRefusesAnEmptyChart() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-empty"));

    expectError(
        this.send(
            multipart("/{repo}/api/charts", repo.getName())
                .part(new MockPart("chart", "chart.tgz", new byte[0]))),
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "helmChartEmpty",
        "helmChartEmpty",
        "The chart part of the request is empty: a chart must have at least one byte.");

    assertNothingStored(repo);
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("Helm classic: a form-typed POST with no chart part is a 400, not a 500")
  void helmRefusesAFormTypedPost() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-form"));

    this.send(
            post("/{repo}/api/charts", repo.getName())
                .contentType(FORM)
                .content(chart("payments", "1.0.0")))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("Missing 'chart' part"));

    assertNothingStored(repo);
  }
}
