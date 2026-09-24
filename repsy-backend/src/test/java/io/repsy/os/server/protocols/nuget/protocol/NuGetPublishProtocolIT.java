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
import static org.mockito.Mockito.mockStatic;
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
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.token.utils.TokenFactory;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
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
  @Autowired private RepoDeployTokenRepository deployTokenRepository;

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

  /**
   * A version {@code 1.0.0} package in which every metadata value, the README and the dependencies
   * carry {@code label}, so two pushes of it never share a value and a row built from the wrong
   * package shows up in an assertion. {@code dependenciesXml} is a {@code <dependencies>} block or
   * empty, and {@code withReadme} decides whether the nuspec declares a README and the archive
   * holds it.
   */
  private record Labelled(String id, String label, String dependenciesXml, boolean withReadme) {

    static final String VERSION = "1.0.0";

    String title() {
      return "Title " + this.label;
    }

    String authors() {
      return "Authors " + this.label;
    }

    String description() {
      return "Description " + this.label;
    }

    String tags() {
      return "tags-" + this.label;
    }

    String iconUrl() {
      return "https://example.test/" + this.label + "/icon.png";
    }

    String licenseUrl() {
      return "https://example.test/" + this.label + "/license";
    }

    String projectUrl() {
      return "https://example.test/" + this.label + "/project";
    }

    String repositoryUrl() {
      return "https://github.com/repsyio/" + this.label;
    }

    String readme() {
      return "# Readme " + this.label;
    }

    String nuspec() {
      return """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%1$s</id>
              <version>%2$s</version>
              <title>%3$s</title>
              <authors>%4$s</authors>
              <description>%5$s</description>
              <tags>%6$s</tags>
              <iconUrl>%7$s</iconUrl>
              <licenseUrl>%8$s</licenseUrl>
              <projectUrl>%9$s</projectUrl>
              <repository type="git" url="%10$s" />
              %11$s
              %12$s
            </metadata>
          </package>
          """
          .formatted(
              this.id,
              VERSION,
              this.title(),
              this.authors(),
              this.description(),
              this.tags(),
              this.iconUrl(),
              this.licenseUrl(),
              this.projectUrl(),
              this.repositoryUrl(),
              this.withReadme ? "<readme>docs/README.md</readme>" : "",
              this.dependenciesXml);
    }

    /** A structurally real package; the README entry is there only when the nuspec declares it. */
    byte[] nupkg() {
      final var entries = new java.util.ArrayList<Entry>();
      entries.add(entry("[Content_Types].xml", "<Types/>"));
      entries.add(entry("_rels/.rels", "<Relationships/>"));
      entries.add(entry(this.id + ".nuspec", this.nuspec()));
      if (this.withReadme) {
        entries.add(entry("docs/README.md", this.readme()));
      }
      entries.add(entry("lib/net8.0/" + this.id + ".dll", "MZ assembly of " + this.label));

      return zip(entries.toArray(Entry[]::new));
    }
  }

  private static String dependencyOn(final String id, final String range, final String framework) {
    return """
        <dependencies>
          <group targetFramework="%s">
            <dependency id="%s" version="%s" />
          </group>
        </dependencies>
        """
        .formatted(framework, id, range);
  }

  private record Entry(String name, byte[] content) {}

  private static Entry entry(final String name, final String content) {
    return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] zip(final Entry... entries) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      for (final var entry : entries) {
        zip.putNextEntry(FixtureZipEntry.named(entry.name()));
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

  /**
   * Inserts a deploy token for {@code repo} in the current test transaction, returns its secret.
   */
  private String seedDeployToken(final Repo repo, final boolean readOnly) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
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

  /**
   * The stored file of {@code pkg}'s version {@code 1.0.0}, {@code extension} being nupkg or
   * nuspec.
   */
  private static java.nio.file.Path storedFile(
      final Repo repo, final Labelled pkg, final String extension) {
    return java.nio.file.Path.of(
        packageDir(repo, pkg.id(), Labelled.VERSION),
        pkg.id().toLowerCase(Locale.ROOT) + "." + Labelled.VERSION + "." + extension);
  }

  /**
   * Asserts that the one row of {@code pkg}'s package holds {@code pkg}'s metadata and README and
   * nothing that only the other push of the pair carries, and returns it for further assertions.
   */
  private NuGetPackageVersion assertMetadataOf(final Repo repo, final Labelled pkg) {
    final var versions = this.storedVersions(repo, pkg.id());

    assertThat(versions).hasSize(1);
    final var stored = versions.getFirst();
    assertThat(stored.getVersion()).isEqualTo(Labelled.VERSION);
    assertThat(stored.getTitle()).isEqualTo(pkg.title());
    assertThat(stored.getAuthors()).isEqualTo(pkg.authors());
    assertThat(stored.getDescription()).isEqualTo(pkg.description());
    assertThat(stored.getTags()).isEqualTo(pkg.tags());
    assertThat(stored.getIconUrl()).isEqualTo(pkg.iconUrl());
    assertThat(stored.getLicenseUrl()).isEqualTo(pkg.licenseUrl());
    assertThat(stored.getProjectUrl()).isEqualTo(pkg.projectUrl());
    assertThat(stored.getRepositoryUrl()).isEqualTo(pkg.repositoryUrl());
    assertThat(stored.getReadme()).isEqualTo(pkg.withReadme() ? pkg.readme() : null);

    return stored;
  }

  /**
   * The dependencies of a stored version, read back through the JSON reader the API uses. That
   * reader turns an unreadable column into an empty list, so a column that holds anything is also
   * required to be a JSON array.
   */
  private static List<NuGetDependencyInfo> dependenciesOf(
      final NuGetPackageVersion stored, final Labelled pkg) {
    if (stored.getDependencies() != null) {
      assertThat(stored.getDependencies()).startsWith("[");
    }

    return NuGetPackageUtils.parseDependenciesJson(
        stored.getDependencies(), pkg.id(), stored.getVersion());
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

    @Test
    @DisplayName("decodes XML-escaped nuspec metadata instead of storing it raw (RPS-1145)")
    void storesXmlEscapedMetadataDecoded() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nuspec =
          """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%1$s</id>
              <version>1.0.0</version>
              <title>%1$s &amp; Co</title>
              <authors>A &amp; B</authors>
              <description>Uses &lt;script&gt; safely &amp; correctly</description>
              <tags>a&amp;b c&amp;d</tags>
            </metadata>
          </package>
          """
              .formatted(id);
      final var nupkg =
          zip(
              entry("[Content_Types].xml", "<Types/>"),
              entry("_rels/.rels", "<Relationships/>"),
              entry(id + ".nuspec", nuspec),
              entry("lib/net8.0/" + id + ".dll", "MZ fixture assembly for " + id));

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getTitle()).isEqualTo(id + " & Co");
                assertThat(v.getAuthors()).isEqualTo("A & B");
                assertThat(v.getDescription()).isEqualTo("Uses <script> safely & correctly");
                assertThat(v.getTags()).isEqualTo("a&b c&d");
              });
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
                assertThat(
                        NuGetPackageUtils.parseDependenciesJson(
                            v.getDependencies(), pkg.id(), v.getVersion()))
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

    /**
     * RPS-1075: the id and version are read with a regular expression, so a nuspec that is not
     * well-formed XML got past those checks and was published with no dependencies at all, without
     * a trace at the default log level.
     */
    @Test
    @DisplayName("rejects a nuspec that is not well-formed XML and stores nothing")
    void rejectsMalformedNuspec() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var malformed =
          zip(
              entry(
                  id + ".nuspec",
                  "<package><metadata><id>%s</id><version>1.0.0</version><dependencies>"
                          .formatted(id)
                      + "<dependency id=\"Serilog\" version=\"3.1.1\"/>"));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, malformed, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("The .nuspec in the package is not well-formed XML."));

      verifyNoInteractions(NuGetPublishProtocolIT.this.usageUpdateService);
      NuGetPublishProtocolIT.this.assertNothingStored(repo, id);
    }
  }

  /**
   * RPS-1053: the nuspec was read whole out of the uploaded package, so a package a few kilobytes
   * long whose nuspec inflates to gigabytes exhausted the heap of the instance. A nuspec over the
   * limit is now refused with a 400 before anything is stored.
   */
  @Nested
  @DisplayName("the size of the nuspec (RPS-1053)")
  class NuspecSize {

    /** A nuspec of exactly {@code size} bytes, padded with trailing whitespace. */
    private static Entry nuspecOfSize(final String id, final long size) {
      final var xml =
          "<package><metadata><id>%s</id><version>1.0.0</version></metadata></package>"
              .formatted(id);

      return entry(id + ".nuspec", xml + " ".repeat((int) size - xml.length()));
    }

    @Test
    @DisplayName("rejects a nuspec over the limit with a 400 and stores nothing")
    void rejectsOversizedNuspec() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var bomb = zip(nuspecOfSize(id, NuGetPackageUtils.MAX_NUSPEC_BYTES + 1));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, bomb, NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("The .nuspec in the package must be at most 1 MiB."));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, id);
    }

    @Test
    @DisplayName("still publishes a nuspec of exactly the limit")
    void publishesNuspecAtLimit() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nupkg = zip(nuspecOfSize(id, NuGetPackageUtils.MAX_NUSPEC_BYTES));

      final var response =
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken());

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id)).hasSize(1);
    }
  }

  /**
   * RPS-1005: a nuspec value longer than its {@code nuget_package_version} column used to fail the
   * row insert, which the facade reported as a 409 while the files stayed in storage. Now a title
   * and tags are cut, a URL is dropped and a version is rejected, all before anything is written.
   *
   * <p>None of these reaches the database with an over-long value, so they run in this class's test
   * transaction. The unrelated-failure case, which does reach it, is in {@link
   * NuGetPublishStorageConsistencyIT}.
   */
  @Nested
  @DisplayName("over-long nuspec metadata (RPS-1005)")
  class OverLongMetadata {

    private static final int URL_COLUMN_LENGTH = 512;

    private static byte[] nupkgWith(final String id, final String metadataXml) {
      final var nuspec =
          """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%s</id>
              <version>1.0.0</version>
              <authors>Repsy</authors>
              <description>over-long metadata fixture</description>
              %s
            </metadata>
          </package>
          """
              .formatted(id, metadataXml);

      return zip(
          entry("[Content_Types].xml", "<Types/>"),
          entry("_rels/.rels", "<Relationships/>"),
          entry(id + ".nuspec", nuspec),
          entry("lib/net8.0/" + id + ".dll", "MZ fixture assembly for " + id));
    }

    private static String urlElements(final String url) {
      return "<iconUrl>%1$s</iconUrl><licenseUrl>%1$s</licenseUrl><projectUrl>%1$s</projectUrl>"
              .formatted(url)
          + "<repository type=\"git\" url=\"%s\" />".formatted(url);
    }

    @Test
    @DisplayName("cuts an over-long title and keeps the nuspec as sent")
    void cutsTitle() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nupkg = nupkgWith(id, "<title>" + "t".repeat(600) + "</title>");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(v -> assertThat(v.getTitle()).isEqualTo("t".repeat(512)));
      assertThat(
              Files.readString(
                  java.nio.file.Path.of(
                      packageDir(repo, id, "1.0.0"), id.toLowerCase() + ".1.0.0.nuspec")))
          .contains("t".repeat(600));
    }

    @Test
    @DisplayName("cuts over-long tags")
    void cutsTags() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nupkg = nupkgWith(id, "<tags>" + "g".repeat(1100) + "</tags>");

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(v -> assertThat(v.getTags()).isEqualTo("g".repeat(1024)));
    }

    @Test
    @DisplayName("drops an over-long icon, license, project and repository URL")
    void dropsUrls() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var nupkg =
          nupkgWith(
              id, "<title>kept</title>" + urlElements("https://example.test/" + "a".repeat(600)));

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getTitle()).isEqualTo("kept");
                assertThat(v.getIconUrl()).isNull();
                assertThat(v.getLicenseUrl()).isNull();
                assertThat(v.getProjectUrl()).isNull();
                assertThat(v.getRepositoryUrl()).isNull();
              });
    }

    @Test
    @DisplayName("keeps every value that is exactly as long as its column")
    void keepsValuesAtTheLimit() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var url = "https://example.test/" + "a".repeat(URL_COLUMN_LENGTH - 21);
      final var nupkg =
          nupkgWith(
              id,
              "<title>"
                  + "t".repeat(512)
                  + "</title><tags>"
                  + "g".repeat(1024)
                  + "</tags>"
                  + urlElements(url));

      assertStatus(
          NuGetPublishProtocolIT.this.pushAs(
              repo, nupkg, NuGetPublishProtocolIT.this.adminProtocolBearerToken()),
          201);

      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, id))
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.getTitle()).isEqualTo("t".repeat(512));
                assertThat(v.getTags()).isEqualTo("g".repeat(1024));
                assertThat(v.getIconUrl()).isEqualTo(url);
                assertThat(v.getLicenseUrl()).isEqualTo(url);
                assertThat(v.getProjectUrl()).isEqualTo(url);
                assertThat(v.getRepositoryUrl()).isEqualTo(url);
              });
    }

    @Test
    @DisplayName("rejects an over-long version with a 400 and stores nothing")
    void rejectsVersion() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0-" + "a".repeat(59));

      NuGetPublishProtocolIT.this
          .protocol(push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[0].message").value("NuGet version is longer than 64 characters."));

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
      // Rejected before the package row is created, so it leaves no empty package behind either.
      assertThat(
              NuGetPublishProtocolIT.this.nugetPackageRepository.findByRepoIdAndPackageIdIgnoreCase(
                  repo.getId(), pkg.id()))
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("repo rules")
  class RepoRules {

    /**
     * The replacement differs from the first package in every nuspec value, the README, the
     * dependencies and the archive, so a rejected push that still touched the row or the files
     * would show in an assertion (RPS-1076).
     */
    @Test
    @DisplayName("rejects a duplicate version with 409 and leaves the first push untouched")
    void rejectsDuplicate() throws Exception {
      // Repos are created with allowOverride on, so switch it off for this one.
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, false, null, null);
      final var id = uniquePackageId();
      final var first =
          new Labelled(id, "first", dependencyOn("Newtonsoft.Json", "13.0.3", "net8.0"), true);
      final var second =
          new Labelled(id, "second", dependencyOn("Serilog", "3.1.1", ".NETStandard2.0"), false);
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();
      final var original = first.nupkg();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, original, token), 201);
      clearInvocations(NuGetPublishProtocolIT.this.usageUpdateService);

      NuGetPublishProtocolIT.this
          .protocol(push(repo, second.nupkg(), token))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("Version 1.0.0 of package " + id + " already exists."));

      final var stored = NuGetPublishProtocolIT.this.assertMetadataOf(repo, first);
      assertThat(dependenciesOf(stored, first))
          .containsExactly(new NuGetDependencyInfo("Newtonsoft.Json", "13.0.3", "net8.0"));
      assertThat(Files.readAllBytes(storedFile(repo, first, "nupkg"))).isEqualTo(original);
      assertThat(Files.readString(storedFile(repo, first, "nuspec"))).isEqualTo(first.nuspec());
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

    /**
     * RPS-1013 pins the same at the service, with a hand-written nuspec. Here the second package
     * goes through the router, the handler and the facade, which read the nuspec and the README out
     * of the pushed {@code .nupkg}, so the row cannot be built from the first package (RPS-1076).
     */
    @Test
    @DisplayName("stores the metadata, README and dependencies of the replacement package")
    void overrideStoresTheReplacementPackageMetadata() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, true, null, null);
      final var id = uniquePackageId();
      final var first =
          new Labelled(id, "first", dependencyOn("Newtonsoft.Json", "13.0.3", "net8.0"), true);
      final var second =
          new Labelled(id, "second", dependencyOn("Serilog", "3.1.1", ".NETStandard2.0"), true);
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, first.nupkg(), token), 201);
      NuGetPublishProtocolIT.this.assertMetadataOf(repo, first);

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, second.nupkg(), token), 201);

      final var stored = NuGetPublishProtocolIT.this.assertMetadataOf(repo, second);
      assertThat(dependenciesOf(stored, second))
          .containsExactly(new NuGetDependencyInfo("Serilog", "3.1.1", ".NETStandard2.0"));
      assertThat(Files.readAllBytes(storedFile(repo, second, "nupkg"))).isEqualTo(second.nupkg());
      assertThat(Files.readString(storedFile(repo, second, "nuspec"))).isEqualTo(second.nuspec());

      NuGetPublishProtocolIT.this
          .perform(
              get("/api/nuget/packages/{repo}/{id}/{version}", repo.getName(), id, "1.0.0")
                  .header(AUTHORIZATION, NuGetPublishProtocolIT.this.adminBearerToken()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.title").value(second.title()))
          .andExpect(jsonPath("$.data.authors").value(second.authors()))
          .andExpect(jsonPath("$.data.description").value(second.description()))
          .andExpect(jsonPath("$.data.readme").value(second.readme()))
          .andExpect(jsonPath("$.data.dependencies", hasSize(1)))
          .andExpect(jsonPath("$.data.dependencies[0].packageId").value("Serilog"))
          .andExpect(jsonPath("$.data.dependencies[0].versionRange").value("3.1.1"))
          .andExpect(jsonPath("$.data.dependencies[0].targetFramework").value(".NETStandard2.0"));
    }

    @Test
    @DisplayName("clears the dependencies and the README when the replacement declares none")
    void overrideWithoutDependenciesOrReadmeClearsThem() throws Exception {
      final var created = NuGetPublishProtocolIT.this.nugetRepo();
      final var repo = NuGetPublishProtocolIT.this.withRepoSettings(created, true, null, null);
      final var id = uniquePackageId();
      final var first =
          new Labelled(id, "first", dependencyOn("Newtonsoft.Json", "13.0.3", "net8.0"), true);
      final var second = new Labelled(id, "second", "", false);
      final var token = NuGetPublishProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, first.nupkg(), token), 201);
      assertThat(NuGetPublishProtocolIT.this.assertMetadataOf(repo, first).getDependencies())
          .startsWith("[");

      assertStatus(NuGetPublishProtocolIT.this.pushAs(repo, second.nupkg(), token), 201);

      // assertMetadataOf also expects the README to be null, as the second package has none.
      final var stored = NuGetPublishProtocolIT.this.assertMetadataOf(repo, second);
      assertThat(stored.getDependencies()).isNull();
      assertThat(dependenciesOf(stored, second)).isEmpty();
      assertThat(Files.readString(storedFile(repo, second, "nuspec"))).isEqualTo(second.nuspec());
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

      NuGetPublishProtocolIT.this.nugetPackageService.publishVersion(
          NuGetPublishProtocolIT.this.repoTxService.getRepoByName(repo.getName()),
          id,
          LEGACY,
          new Pkg(id, LEGACY).nuspec(),
          null,
          replacesExisting -> BaseUsages.ofDisk(0));

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

  /**
   * RPS-1146: {@code createNuGetPackageVersion} used to catch a dependency-JSON serialization
   * failure, warn, and store the version with a {@code null} dependencies column anyway. It now
   * lets the failure propagate, so the push fails instead of silently losing the dependencies the
   * client sent.
   */
  @Nested
  @DisplayName("dependency serialization failure (RPS-1146)")
  class DependencySerializationFailure {

    @Test
    @DisplayName("fails the push instead of storing null dependencies, and stores nothing")
    void failsInsteadOfStoringNullDependencies() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0", true);

      try (var utils = mockStatic(NuGetPackageUtils.class, Mockito.CALLS_REAL_METHODS)) {
        utils
            .when(() -> NuGetPackageUtils.toDependenciesJson(any()))
            .thenThrow(new IllegalStateException("mapper misconfigured"));

        NuGetPublishProtocolIT.this
            .protocol(
                push(repo, pkg.nupkg(), NuGetPublishProtocolIT.this.adminProtocolBearerToken()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errors[0].message").value("Publish failed"));
      }

      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
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

    /**
     * RPS-1214: pins the panel's "Option B" contract at the protocol level. {@code X-NuGet-ApiKey}
     * is fed through {@code NuGetAuthPreProcessor}'s Bearer path, and a deploy token secret is a
     * valid bearer credential there -- this is the case the panel's {@code --api-key
     * "<YOUR_DEPLOY_TOKEN>"} documents.
     */
    @Test
    @DisplayName("accepts a deploy token secret in the X-NuGet-ApiKey header (RPS-1214)")
    void apiKeyHeaderWithDeployTokenSecret() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");
      final var secret = NuGetPublishProtocolIT.this.seedDeployToken(repo, false);

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(API_KEY_HEADER, secret))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    /**
     * RPS-1214: the flip side of the contract above. A user/admin password is not a valid bearer
     * credential (it is not a deploy token, a protocol JWT, nor "Basic "-prefixed), so it is
     * refused with the same 401/Basic-challenge shape as every other bad credential here -- this is
     * why the panel's old "Option B" copy, which told users to pass their password as {@code
     * --api-key}, was wrong and has been corrected to the deploy token only.
     */
    @Test
    @DisplayName(
        "rejects a user password sent in the X-NuGet-ApiKey header, with a Basic "
            + "challenge (RPS-1214)")
    void apiKeyHeaderRejectsUserPassword() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.privateNugetRepo();
      NuGetPublishProtocolIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(API_KEY_HEADER, VALID_PASSWORD))
              .andReturn()
              .getResponse();

      assertStatus(response, 401);
      assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(BASIC_CHALLENGE);
      NuGetPublishProtocolIT.this.assertNothingStored(repo, pkg.id());
    }

    /**
     * RPS-1214: {@code normalizeAuthHeader} leaves a value that already starts with {@code "Basic
     * "} untouched, so it dispatches to {@code handleBasicAuth} instead of the Bearer path -- the
     * documented escape hatch for a password credential in Option B.
     */
    @Test
    @DisplayName("accepts a Basic-prefixed value in the X-NuGet-ApiKey header (RPS-1214)")
    void apiKeyHeaderAcceptsBasicPrefixedValue() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.privateNugetRepo();
      final var user =
          NuGetPublishProtocolIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(API_KEY_HEADER, basic(user.getUsername(), VALID_PASSWORD)))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }

    /**
     * RPS-1214: pins {@code extractAuthHeader}'s precedence, which was untested before. A garbage
     * {@code X-NuGet-ApiKey} value must not interfere when a valid {@code Authorization} header is
     * also present.
     */
    @Test
    @DisplayName("prefers Authorization over X-NuGet-ApiKey when both are present (RPS-1214)")
    void authorizationHeaderTakesPrecedenceOverApiKey() throws Exception {
      final var repo = NuGetPublishProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0");

      final var response =
          NuGetPublishProtocolIT.this
              .protocol(
                  multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                      .part(new MockPart(PACKAGE_PART, "package.nupkg", pkg.nupkg()))
                      .header(AUTHORIZATION, NuGetPublishProtocolIT.this.adminProtocolBearerToken())
                      .header(API_KEY_HEADER, "not-a-real-token"))
              .andReturn()
              .getResponse();

      assertStatus(response, 201);
      assertThat(NuGetPublishProtocolIT.this.storedVersions(repo, pkg.id())).hasSize(1);
    }
  }
}
