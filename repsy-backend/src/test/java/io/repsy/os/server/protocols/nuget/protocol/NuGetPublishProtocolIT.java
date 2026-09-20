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
package io.repsy.os.server.protocols.nuget.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.server.protocols.nuget.ui.facades.NuGetApiFacade;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full-stack Testcontainers coverage for the NuGet V3 wire-protocol push ({@code PUT
 * /{repo}/v3/package}), the request {@code nuget push} sends.
 *
 * <p>RPS-868 covers the {@code /api/nuget/packages/*} management API, whose fixtures cannot be real
 * packages without a push path that goes through the protocol router. This class is that path: it
 * pushes genuine {@code .nupkg} archives as multipart requests on the protocol port, so the
 * router's path parser, the auth pre-processor, the publish handler, the facade, the storage
 * backend and the usage post-processor all run, and then asserts the database rows, the stored
 * files and the recorded usage, and reads the package back through the download and version-list
 * endpoints.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage delta the usage post-processor requests, which
 * is what "repo usage updated" means here.
 */
@DisplayName("NuGet wire protocol PUT /{repo}/v3/package")
class NuGetPublishProtocolIT extends AbstractIntegrationTest {

  private static final int PROTOCOL_PORT = 9090;
  private static final String PUSH_PATH = "/{repo}/v3/package";
  private static final String PACKAGE_PART = "package";
  private static final String API_KEY_HEADER = "X-NuGet-ApiKey";
  private static final String BASIC_CHALLENGE = "Basic realm=\"Repsy Managed Repository\"";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private NuGetStorageService nugetStorageService;

  @Autowired private NuGetPackageService<UUID> nugetPackageService;
  @Autowired private NuGetApiFacade nugetApiFacade;
  @Autowired private RepoTxService repoTxService;
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * A package to build: {@code id}/{@code version} go into the nuspec verbatim, and {@code
   * withDependency} adds a {@code <dependencies>} block.
   */
  private record Pkg(String id, String version, boolean withDependency) {

    Pkg(final String id, final String version) {
      this(id, version, false);
    }

    String nuspec() {
      final var dependencies =
          this.withDependency
              ? """
                  <dependencies>
                    <group targetFramework="net8.0">
                      <dependency id="Newtonsoft.Json" version="13.0.3" />
                    </group>
                  </dependencies>
                """
              : "";

      return """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%s</id>
              <version>%s</version>
              <title>%s title</title>
              <authors>Repsy</authors>
              <description>wire protocol fixture</description>
              <tags>fixture wire</tags>
          %s  </metadata>
          </package>
          """
          .formatted(this.id, this.version, this.id, dependencies);
    }

    /** A structurally real package: content types, relationships, nuspec and one assembly. */
    byte[] nupkg() {
      return zip(
          entry("[Content_Types].xml", "<Types/>"),
          entry("_rels/.rels", "<Relationships/>"),
          entry(this.id + ".nuspec", this.nuspec()),
          entry("lib/net8.0/" + this.id + ".dll", "MZ fixture assembly for " + this.id));
    }
  }

  private record Entry(String name, byte[] content) {}

  private static Entry entry(final String name, final String content) {
    return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] zip(final Entry... entries) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      for (final var entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.content());
        zip.closeEntry();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static String uniquePackageId() {
    return "Repsy.Fixture" + randomTag();
  }

  /**
   * Protocol requests are served by the protocol router on the main port (9090), which resolves the
   * repo from the servlet path.
   */
  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  /**
   * Repos created by {@link #committedRepo}. They outlive the rolled-back test transactions, so
   * {@link #deleteCommittedRepos} removes them once the class is done.
   */
  private static final List<UUID> COMMITTED_REPOS = new CopyOnWriteArrayList<>();

  @AfterAll
  static void deleteCommittedRepos(@Autowired final JdbcTemplate jdbcTemplate) {
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    COMMITTED_REPOS.forEach(id -> jdbcTemplate.update("delete from repo where id = ?", id));
    COMMITTED_REPOS.clear();
  }

  /**
   * Creates a NuGet repo in a transaction of its own, so it is committed before the test runs.
   *
   * <p>A push cannot run against a repo that only exists in the test's transaction: {@code
   * NuGetPackageServiceImpl} inserts the {@code nuget_package} row in a {@code REQUIRES_NEW}
   * transaction (so two concurrent first pushes of a package cannot fail each other), and that
   * transaction cannot see the uncommitted repo the row references.
   */
  private Repo committedRepo(final String name, final boolean privateRepo) {
    final var template = new TransactionTemplate(this.transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    final var created =
        template.execute(
            _ -> {
              final var repo =
                  this.repoTxService.createRepo(name, RepoType.NUGET, privateRepo, null);
              this.nugetStorageService.createRepo(repo.getId());
              return repo;
            });
    COMMITTED_REPOS.add(Objects.requireNonNull(created).getId());

    return this.reloadRepo(name);
  }

  private Repo nugetRepo() {
    return this.committedRepo(uniqueRepoName("nuget"), false);
  }

  private Repo privateNugetRepo() {
    return this.committedRepo(uniqueRepoName("nuget-priv"), true);
  }

  private Repo withRepoSettings(
      final Repo repo,
      final boolean allowOverride,
      final Boolean releases,
      final Boolean snapshots) {

    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setAllowOverride(allowOverride);
    managed.setReleases(releases);
    managed.setSnapshots(snapshots);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  private static String basic(final String username, final String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static AbstractMockHttpServletRequestBuilder<?> push(
      final Repo repo, final byte[] nupkg, final String authorization) {
    return push(repo, new MockPart(PACKAGE_PART, "package.nupkg", nupkg), authorization);
  }

  private static AbstractMockHttpServletRequestBuilder<?> push(
      final Repo repo, final MockPart part, final String authorization) {
    final var request = multipart(HttpMethod.PUT, PUSH_PATH, repo.getName()).part(part);

    return authorization == null ? request : request.header(AUTHORIZATION, authorization);
  }

  private MockHttpServletResponse pushAs(final Repo repo, final byte[] nupkg, final String auth)
      throws Exception {
    return this.protocol(push(repo, nupkg, auth)).andReturn().getResponse();
  }

  private static void assertStatus(final MockHttpServletResponse response, final int expected)
      throws Exception {
    assertThat(response.getStatus())
        .as("status, body: %s", response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(expected);
  }

  private static String packageDir(final Repo repo, final String id, final String version) {
    return storageDirOf(repo)
        .resolve("packages")
        .resolve(id.toLowerCase(java.util.Locale.ROOT))
        .resolve(version)
        .toString();
  }

  private List<NuGetPackageVersion> storedVersions(final Repo repo, final String id) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.nugetPackageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repo.getId(), id)
        .map(
            pkg ->
                this.nugetPackageVersionRepository.findByNugetPackageIdOrderByPublishedAtDesc(
                    pkg.getId()))
        .orElse(List.of());
  }

  private void assertNothingStored(final Repo repo, final String id) {
    assertThat(this.storedVersions(repo, id)).isEmpty();
    assertThat(
            storageDirOf(repo).resolve("packages").resolve(id.toLowerCase(java.util.Locale.ROOT)))
        .doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Nested
  @DisplayName("a successful push")
  class SuccessfulPush {

    @Test
    @DisplayName("stores the rows, the files and the usage of a real package")
    void storesPackage() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.2.3");
      final var nupkg = pkg.nupkg();

      final var response =
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken());

      assertStatus(response, 201);

      final var versions = NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id());
      assertThat(versions).hasSize(1);
      final var stored = versions.getFirst();
      assertThat(stored.getVersion()).isEqualTo("1.2.3");
      assertThat(stored.isPrerelease()).isFalse();
      assertThat(stored.isListed()).isTrue();
      assertThat(stored.getTitle()).isEqualTo(pkg.id() + " title");
      assertThat(stored.getAuthors()).isEqualTo("Repsy");
      assertThat(stored.getDescription()).isEqualTo("wire protocol fixture");
      assertThat(stored.getNugetPackage().getPackageId()).isEqualTo(pkg.id().toLowerCase());

      final var dir = packageDir(repo, pkg.id(), "1.2.3");
      final var stem = pkg.id().toLowerCase() + ".1.2.3";
      final var nupkgFile = java.nio.file.Path.of(dir, stem + ".nupkg");
      final var nuspecFile = java.nio.file.Path.of(dir, stem + ".nuspec");
      assertThat(Files.readAllBytes(nupkgFile)).isEqualTo(nupkg);
      assertThat(Files.readString(nuspecFile)).isEqualTo(pkg.nuspec());

      verify(NuGetPublishProtocolIT.this.usageUpdateService)
          .updateUsage(
              new UsageChangedInfo(
                  repo.getId(), BaseUsages.ofDisk(Files.size(nupkgFile) + Files.size(nuspecFile))));
    }

    @Test
    @DisplayName("stores the repository URL and the README declared in the nuspec")
    void storesRepositoryUrlAndReadme() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nuspec =
          """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%s</id>
              <version>1.0.0</version>
              <authors>Repsy</authors>
              <description>readme fixture</description>
              <readme>docs\\README.md</readme>
              <repository type="git" url="https://github.com/repsyio/%s.git" commit="abc123" />
            </metadata>
          </package>
          """
              .formatted(id, id);
      final var nupkg =
          zip(
              entry("[Content_Types].xml", "<Types/>"),
              entry("_rels/.rels", "<Relationships/>"),
              entry(id + ".nuspec", nuspec),
              entry("docs/README.md", "# " + id + "\n\nShips a readme."),
              entry("lib/net8.0/" + id + ".dll", "MZ fixture assembly"));

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getRepositoryUrl())
                    .isEqualTo("https://github.com/repsyio/" + id + ".git");
                assertThat(v.getReadme()).isEqualTo("# " + id + "\n\nShips a readme.");
              });
    }

    @Test
    @DisplayName("still publishes when the declared README is missing from the package")
    void publishesWithoutDeclaredReadme() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nupkg =
          zip(
              entry("[Content_Types].xml", "<Types/>"),
              entry(
                  id + ".nuspec",
                  "<package><metadata><id>%s</id><version>1.0.0</version><readme>README.md</readme>"
                          .formatted(id)
                      + "</metadata></package>"));

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(v -> assertThat(v.getReadme()).isNull());
    }

    /**
     * A substring check on the column is not enough: an XML rendering of the same dependencies
     * contains the same words. So the stored value must parse back through the JSON reader, and the
     * management API, which reads it the same way, must return the dependency.
     */
    @Test
    @DisplayName("stores the declared dependencies as JSON and returns them from the API")
    void storesDependencies() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0", true);

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id()))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getDependencies()).startsWith("[");
                assertThat(NuGetPackageUtils.parseDependenciesJson(v.getDependencies()))
                    .containsExactly(
                        new NuGetDependencyInfo("Newtonsoft.Json", "13.0.3", "net8.0"));
              });

      NuGetPublishProtocolIT.this
          .perform(
              get("/api/nuget/packages/{repo}/{id}/{version}", repo.getName(), pkg.id(), "1.0.0")
                  .header(AUTHORIZATION, NuGetPublishProtocolIT.this.adminBearerToken()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.dependencies", hasSize(1)))
          .andExpect(jsonPath("$.data.dependencies[0].packageId").value("Newtonsoft.Json"))
          .andExpect(jsonPath("$.data.dependencies[0].versionRange").value("13.0.3"))
          .andExpect(jsonPath("$.data.dependencies[0].targetFramework").value("net8.0"));
    }

    @Test
    @DisplayName("flags a pre-release version")
    void flagsPrerelease() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "2.0.0-beta.1");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      final var versions = NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id());
      assertThat(versions).singleElement().satisfies(v -> assertThat(v.isPrerelease()).isTrue());
    }

    @Test
    @DisplayName("normalizes a four-part version to its canonical form")
    void normalizesVersion() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id()))
          .singleElement()
          .satisfies(v -> assertThat(v.getVersion()).isEqualTo("1.0.0"));
      assertThat(java.nio.file.Path.of(packageDir(repo, pkg.id(), "1.0.0")))
          .isDirectory()
          .isNotEmptyDirectory();
    }

    @Test
    @DisplayName("accepts a two-part version and stores it as its three-part form (RPS-949)")
    void acceptsTwoPartVersion() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id()))
          .singleElement()
          .satisfies(v -> assertThat(v.getVersion()).isEqualTo("1.0.0"));
      assertThat(java.nio.file.Path.of(packageDir(repo, pkg.id(), "1.0.0")))
          .isDirectory()
          .isNotEmptyDirectory();
    }

    @Test
    @DisplayName(
        "drops the build metadata, so 1.0.0+Build.5 is stored and served as 1.0.0 (RPS-996)")
    void dropsBuildMetadata() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0+Build.5");
      final var nupkg = pkg.nupkg();
      final var lowerId = pkg.id().toLowerCase();

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id()))
          .singleElement()
          .satisfies(v -> assertThat(v.getVersion()).isEqualTo("1.0.0"));
      assertThat(java.nio.file.Path.of(packageDir(repo, pkg.id(), "1.0.0")))
          .isDirectory()
          .isNotEmptyDirectory();
      assertThat(java.nio.file.Path.of(packageDir(repo, pkg.id(), "1.0.0+build.5"))).doesNotExist();

      NuGetPublishProtocolIT.this
          .protocol(get("/{repo}/v3/package/{id}/index.json", repo.getName(), lowerId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.versions", hasSize(1)))
          .andExpect(jsonPath("$.versions[0]").value("1.0.0"));

      // What a NuGet client asks for, and what the URL of the pushed version spells out.
      for (final var version : List.of("1.0.0", "1.0.0+Build.5")) {
        final var downloaded =
            NuGetPublishProtocolIT.this
                .protocol(
                    get(
                        "/{repo}/v3/package/{id}/{version}/{id}.{version}.nupkg",
                        repo.getName(),
                        lowerId,
                        version,
                        lowerId,
                        version))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(downloaded).as("download of %s", version).isEqualTo(nupkg);
      }
    }

    @Test
    @DisplayName("does not take a dash in the build metadata for a pre-release marker (RPS-996)")
    void dashInBuildMetadataIsNotPrerelease() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0+a-b");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id()))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getVersion()).isEqualTo("1.0.0");
                assertThat(v.isPrerelease()).isFalse();
              });
    }

    @Test
    @DisplayName("can be read back through the version list and the download endpoints")
    void readBack() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "3.1.4");
      final var nupkg = pkg.nupkg();
      final var lowerId = pkg.id().toLowerCase();

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      NuGetPublishProtocolIT.this
          .protocol(get("/{repo}/v3/package/{id}/index.json", repo.getName(), lowerId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.versions[0]").value("3.1.4"));

      final var downloaded =
          NuGetPublishProtocolIT.this
              .protocol(
                  get(
                      "/{repo}/v3/package/{id}/3.1.4/{id}.3.1.4.nupkg",
                      repo.getName(),
                      lowerId,
                      lowerId))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsByteArray();
      assertThat(downloaded).isEqualTo(nupkg);
    }

    @Test
    @DisplayName("accepts several versions of one package")
    void severalVersions() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(repo, new Pkg(id, "1.0.0").nupkg(), token), 201);
      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(repo, new Pkg(id, "1.1.0").nupkg(), token), 201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .extracting(NuGetPackageVersion::getVersion)
          .containsExactlyInAnyOrder("1.0.0", "1.1.0");
    }
  }

  @Nested
  @DisplayName("multipart handling")
  class MultipartHandling {

    @Test
    @DisplayName("uses the part named package even when it is not the first one")
    void picksNamedPart() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart("symbols", "symbols.snupkg", new byte[] {1, 2, 3}))
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(
                          AUTHORIZATION, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    @Test
    @DisplayName("falls back to the first part when none is named package")
    void fallsBackToFirstPart() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  push(
                      repo,
                      new MockPart("file", "package.nupkg", pkg.nupkg()),
                      NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    @Test
    @DisplayName("rejects a request that is not multipart")
    void rejectsNonMultipart() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      NuGetPublishProtocolIT.this
          .protocol(
              put(PUSH_PATH, repo.getName())
                  .contentType("application/octet-stream")
                  .content(pkg.nupkg())
                  .header(AUTHORIZATION, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message").value("Content-Type must be multipart/form-data"));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("rejects a multipart request without any part")
    void rejectsMissingPart() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();

      NuGetPublishProtocolIT.this
          .protocol(
              multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                  .header(AUTHORIZATION, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("Missing package content."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("rejects an empty package part")
    void rejectsEmptyPart() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();

      NuGetPublishProtocolIT.this
          .protocol(push(repo, new byte[0], NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("NuGet package stream is empty."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }
  }

  @Nested
  @DisplayName("package validation")
  class PackageValidation {

    @Test
    @DisplayName("rejects content that is not a zip archive")
    void rejectsNonZip() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();

      NuGetPublishProtocolIT.this
          .protocol(
              push(
                  repo,
                  "definitely not a nupkg".getBytes(StandardCharsets.UTF_8),
                  NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("The uploaded file is not a valid NuGet package (.nuspec not found)."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("rejects an archive without a nuspec")
    void rejectsMissingNuspec() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var noNuspec = zip(entry("lib/net8.0/x.dll", "MZ"));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, noNuspec, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("The uploaded file is not a valid NuGet package (.nuspec not found)."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("rejects a nuspec without an id or a version")
    void rejectsIncompleteNuspec() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var incomplete =
          zip(entry("x.nuspec", "<package><metadata><id>Only.Id</id></metadata></package>"));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, incomplete, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("Missing 'id' or 'version' in nuspec."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("rejects an invalid package id")
    void rejectsInvalidId() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg("bad id!", "1.0.0");

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("Invalid NuGet package id."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("rejects an invalid version")
    void rejectsInvalidVersion() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "one.two");

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("Invalid NuGet version format."));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }
  }

  @Nested
  @DisplayName("repo rules")
  class RepoRules {

    @Test
    @DisplayName("rejects a duplicate version with 409 and leaves the first push untouched")
    void rejectsDuplicate() throws Exception {
      // Repos are created with allowOverride on, so switch it off for this one.
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, false, null, null);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();
      final var original = pkg.nupkg();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, original, token), 201);
      clearInvocations(NuGetPublishProtocolIT.this.usageUpdateService);

      final var replacement =
          zip(
              entry(pkg.id() + ".nuspec", pkg.nuspec()),
              entry("lib/net8.0/other.dll", "different content"));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, replacement, token))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("Version 1.0.0 of package " + pkg.id() + " already exists."));

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
      assertThat(
              Files.readAllBytes(
                  java.nio.file.Path.of(
                      packageDir(repo, pkg.id(), "1.0.0"),
                      pkg.id().toLowerCase() + ".1.0.0.nupkg")))
          .isEqualTo(original);
      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("treats 1.0.0+a and 1.0.0+b as one version and rejects the second (RPS-996)")
    void rejectsSameVersionWithOtherBuildMetadata() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, false, null, null);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0+a");
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      NuGetPublishProtocolIT.this
          .protocol(push(repo, new Pkg(pkg.id(), "1.0.0+b").nupkg(), token))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("Version 1.0.0 of package " + pkg.id() + " already exists."));
      NuGetPublishProtocolIT.this
          .protocol(push(repo, new Pkg(pkg.id(), "1.0.0").nupkg(), token))
          .andExpect(status().isConflict());

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    @Test
    @DisplayName("replaces 1.0.0 with 1.0.0+build when the repo allows overrides (RPS-996)")
    void overridesVersionWithOtherBuildMetadata() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, true, null, null);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      final var replacement = new Pkg(pkg.id(), "1.0.0+build").nupkg();
      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, replacement, token), 201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
      assertThat(
              Files.readAllBytes(
                  java.nio.file.Path.of(
                      packageDir(repo, pkg.id(), "1.0.0"),
                      pkg.id().toLowerCase() + ".1.0.0.nupkg")))
          .isEqualTo(replacement);
    }

    @Test
    @DisplayName("replaces an existing version when the repo allows overrides")
    void overridesWhenAllowed() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, true, null, null);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      final var replacement =
          zip(
              entry(pkg.id() + ".nuspec", pkg.nuspec()),
              entry("lib/net8.0/other.dll", "different content"));
      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, replacement, token), 201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
      assertThat(
              Files.readAllBytes(
                  java.nio.file.Path.of(
                      packageDir(repo, pkg.id(), "1.0.0"),
                      pkg.id().toLowerCase() + ".1.0.0.nupkg")))
          .isEqualTo(replacement);
    }

    @Test
    @DisplayName("rejects a pre-release when the repo does not accept snapshots")
    void rejectsPrereleaseWhenDisabled() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, false, null, false);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0-rc.1");

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isUnprocessableContent())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("Pre-release packages are not allowed in this repository."));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("rejects a release when the repo does not accept releases")
    void rejectsReleaseWhenDisabled() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, false, false, null);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isUnprocessableContent())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("Release packages are not allowed in this repository."));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }
  }

  /**
   * Versions published before RPS-996 kept their build metadata: the row says {@code 1.0.0+legacy}
   * and the files sit in a {@code 1.0.0+legacy} directory. They have to stay readable and
   * deletable, and deleting one must not touch the canonical {@code 1.0.0} it now collides with.
   */
  @Nested
  @DisplayName("a version stored with build metadata before RPS-996")
  class LegacyBuildMetadata {

    private static final String LEGACY = "1.0.0+legacy";

    private record Seeded(Repo repo, String id, byte[] legacyNupkg, byte[] canonicalNupkg) {}

    private Seeded seed() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var lowerId = id.toLowerCase();
      final var canonical = new Pkg(id, "1.0.0").nupkg();
      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, canonical, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      final var legacy = new Pkg(id, LEGACY).nupkg();
      final var directory = java.nio.file.Path.of(packageDir(repo, id, LEGACY));
      Files.createDirectories(directory);
      Files.write(directory.resolve(lowerId + "." + LEGACY + ".nupkg"), legacy);
      Files.writeString(
          directory.resolve(lowerId + "." + LEGACY + ".nuspec"), new Pkg(id, LEGACY).nuspec());

      final var packageId =
          NuGetPublishProtocolIT.this
              .nugetPackageRepository
              .findByRepoIdAndPackageIdIgnoreCase(repo.getId(), lowerId)
              .orElseThrow()
              .getId();
      NuGetPublishProtocolIT.this.nugetPackageService.publishVersion(
          NuGetPublishProtocolIT.this.repoTxService.getRepoByName(repo.getName()),
          packageId,
          LEGACY,
          new Pkg(id, LEGACY).nuspec(),
          null);

      return new Seeded(repo, id, legacy, canonical);
    }

    private byte[] download(final Seeded seeded, final String version) throws Exception {
      final var lowerId = seeded.id().toLowerCase();

      return NuGetPublishProtocolIT.this
          .protocol(
              get(
                  "/{repo}/v3/package/{id}/{version}/{id}.{version}.nupkg",
                  seeded.repo().getName(),
                  lowerId,
                  version,
                  lowerId,
                  version))
          .andExpect(status().isOk())
          .andReturn()
          .getResponse()
          .getContentAsByteArray();
    }

    @Test
    @DisplayName("is still served under its own version, next to the canonical one")
    void servesLegacyVersion() throws Exception {
      final var seeded = this.seed();

      assertThat(this.download(seeded, LEGACY)).isEqualTo(seeded.legacyNupkg());
      assertThat(this.download(seeded, "1.0.0")).isEqualTo(seeded.canonicalNupkg());

      assertThat(NuGetPublishProtocolIT.this.storedVersions(seeded.repo(), seeded.id()))
          .filteredOn(v -> LEGACY.equals(v.getVersion()))
          .singleElement()
          .satisfies(v -> assertThat(v.getDownloadCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("is deleted from its own directory and leaves the canonical version alone")
    void deletesLegacyVersion() throws Exception {
      final var seeded = this.seed();
      final var repo = seeded.repo();

      NuGetPublishProtocolIT.this.nugetApiFacade.deleteVersion(
          NuGetPublishProtocolIT.this.repoTxService.getRepoByName(repo.getName()),
          seeded.id(),
          LEGACY);

      assertThat(java.nio.file.Path.of(packageDir(repo, seeded.id(), LEGACY))).doesNotExist();
      assertThat(java.nio.file.Path.of(packageDir(repo, seeded.id(), "1.0.0")))
          .isDirectory()
          .isNotEmptyDirectory();
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, seeded.id()))
          .extracting(NuGetPackageVersion::getVersion)
          .containsExactly("1.0.0");
    }
  }

  @Nested
  @DisplayName("an unexpected failure")
  @ExtendWith(OutputCaptureExtension.class)
  class UnexpectedFailure {

    @Test
    @DisplayName("answers 500 'Publish failed' and logs the exception with its stack trace")
    void logsTheException(final CapturedOutput output) throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      doThrow(new IllegalStateException("storage backend down"))
          .when(NuGetPublishProtocolIT.this.nugetStorageService)
          .writePackage(any(), any(), any(), any(), any());

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.errors[0].message").value("Publish failed"));

      assertThat(output.getAll())
          .contains("ERROR")
          .contains("NuGet publish failed")
          .contains("java.lang.IllegalStateException: storage backend down");
      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
    }
  }

  @Nested
  @DisplayName("authentication")
  class Authentication {

    @Test
    @DisplayName("answers 401 with a Basic challenge when no credentials are sent")
    void noCredentials() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), null))
          .andExpect(status().isUnauthorized())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                  .string("WWW-Authenticate", BASIC_CHALLENGE));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("answers 401 for a public repo too, since a push is a write")
    void publicRepoStillNeedsCredentials() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      assertThat(repo.isPrivateRepo()).isFalse();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, pkg.nupkg(), null), 401);

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("answers 401 for a token that is not valid")
    void invalidToken() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(repo, pkg.nupkg(), "Bearer not-a-real-token"), 401);

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("answers 401 for an expired token")
    void expiredToken() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var user =
          NuGetPublishProtocolIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), NuGetPublishProtocolIT.this.expiredBearerTokenFor(user)),
          401);

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("answers 401 for a wrong Basic password")
    void wrongPassword() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var user =
          NuGetPublishProtocolIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), basic(user.getUsername(), "not-the-password")),
          401);

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    @Test
    @DisplayName("accepts Basic credentials")
    void basicCredentials() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.privateNugetRepo();
      final var user =
          NuGetPublishProtocolIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, pkg.nupkg(), basic(user.getUsername(), VALID_PASSWORD)),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    @Test
    @DisplayName("accepts a token sent in the X-NuGet-ApiKey header, as nuget push -ApiKey does")
    void apiKeyHeader() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      final var token =
          NuGetPublishProtocolIT.this
              .adminProtocolBearerToken()
              .substring(io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER.length());

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(API_KEY_HEADER, token))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }
  }
}
