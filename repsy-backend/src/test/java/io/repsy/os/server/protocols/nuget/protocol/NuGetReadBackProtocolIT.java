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
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full-stack coverage of what a NuGet client actually reads back after a real push, on the protocol
 * port: the registration leaf ({@code /v3/registration/{id}/{version}.json}, built by {@code
 * AbstractNuGetProtocolFacade#getRegistrationLeaf} from the version row), the flat-container
 * version list ({@code /v3/package/{id}/index.json}) and the {@code .nupkg} / {@code .nuspec}
 * downloads.
 *
 * <p>{@link NuGetPublishProtocolIT} pins the database rows, the stored files and the panel API of a
 * push and an override (RPS-905, RPS-1076); {@link NuGetReadProtocolIT} pins the failure paths of
 * these same wire endpoints (RPS-997). Neither asserts the happy-path response body a {@code nuget
 * restore} actually consumes, so a leaf mapper or a cache serving stale data after an override
 * (RPS-948) would show up nowhere (RPS-1147).
 *
 * <p>Ordering of leaves and versions is RPS-1130's concern, not this class's: every push here is a
 * single version per package, so there is nothing to order.
 */
@DisplayName("NuGet wire protocol read-back of a pushed package")
class NuGetReadBackProtocolIT extends AbstractIntegrationTest {

  private static final String PUSH_PATH = "/{repo}/v3/package";
  private static final String PACKAGE_PART = "package";
  private static final String REGISTRATION_LEAF_PATH =
      "/{repo}/v3/registration/{id}/{version}.json";
  private static final String VERSIONS_PATH = "/{repo}/v3/package/{id}/index.json";
  private static final String NUPKG_PATH = "/{repo}/v3/package/{id}/{version}/{id}.{version}.nupkg";
  private static final String NUSPEC_PATH =
      "/{repo}/v3/package/{id}/{version}/{id}.{version}.nuspec";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private NuGetStorageService nugetStorageService;
  @Autowired private RepoTxService repoTxService;
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * A package to build: {@code label} flows into every nuspec value so two pushes of it never share
   * a value and a response built from the wrong push shows up in an assertion. {@code
   * dependenciesXml} is a {@code <dependencies>} block, or empty for none.
   */
  private record Pkg(String id, String version, String label, String dependenciesXml) {

    Pkg(final String id, final String version, final String label) {
      this(id, version, label, "");
    }

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

    String nuspec() {
      return """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
            <metadata>
              <id>%s</id>
              <version>%s</version>
              <title>%s</title>
              <authors>%s</authors>
              <description>%s</description>
              <tags>%s</tags>
          %s  </metadata>
          </package>
          """
          .formatted(
              this.id,
              this.version,
              this.title(),
              this.authors(),
              this.description(),
              this.tags(),
              this.dependenciesXml);
    }

    /** A structurally real package: content types, relationships, nuspec and one assembly. */
    byte[] nupkg() {
      return zip(
          entry("[Content_Types].xml", "<Types/>"),
          entry("_rels/.rels", "<Relationships/>"),
          entry(this.id + ".nuspec", this.nuspec()),
          entry("lib/net8.0/" + this.id + ".dll", "MZ fixture assembly for " + this.label));
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
    return "Repsy.ReadBack" + randomTag();
  }

  /**
   * Repos created by {@link #nugetRepo}. They outlive the rolled-back test transactions, so {@link
   * #deleteCommittedRepos} removes them once the class is done.
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
   * <p>A push cannot run against a repo that only exists in the test's transaction: the row a first
   * push inserts is written in a transaction that cannot see the uncommitted repo it references
   * (see {@code NuGetPublishProtocolIT#committedRepo}, which this mirrors).
   */
  private Repo nugetRepo() {
    final var name = uniqueRepoName("nuget-readback");
    final var template = new TransactionTemplate(this.transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    final var created =
        template.execute(
            _ -> {
              final var repo = this.repoTxService.createRepo(name, RepoType.NUGET, false, null);
              this.nugetStorageService.createRepo(repo.getId());
              return repo;
            });
    COMMITTED_REPOS.add(Objects.requireNonNull(created).getId());

    return this.reloadRepo(name);
  }

  /** Repos are created with {@code allowOverride} on; this switches it off. */
  private Repo withOverrideDisabled(final Repo repo) {
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setAllowOverride(false);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private MockHttpServletResponse pushAs(final Repo repo, final byte[] nupkg, final String auth)
      throws Exception {
    final var request =
        multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
            .part(new MockPart(PACKAGE_PART, "package.nupkg", nupkg))
            .header(AUTHORIZATION, auth);

    return this.protocol(request).andReturn().getResponse();
  }

  private static void assertStatus(final MockHttpServletResponse response, final int expected)
      throws Exception {
    assertThat(response.getStatus())
        .as("status, body: %s", response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(expected);
  }

  private ResultActions readRegistrationLeaf(final Repo repo, final Pkg pkg) throws Exception {
    return this.protocol(
        get(
                REGISTRATION_LEAF_PATH,
                repo.getName(),
                pkg.id().toLowerCase(Locale.ROOT),
                pkg.version())
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private ResultActions readVersionList(final Repo repo, final Pkg pkg) throws Exception {
    return this.protocol(
        get(VERSIONS_PATH, repo.getName(), pkg.id().toLowerCase(Locale.ROOT))
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private byte[] downloadNupkg(final Repo repo, final Pkg pkg) throws Exception {
    return this.protocol(
            get(
                    NUPKG_PATH,
                    repo.getName(),
                    pkg.id().toLowerCase(Locale.ROOT),
                    pkg.version(),
                    pkg.id().toLowerCase(Locale.ROOT),
                    pkg.version())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  private String downloadNuspec(final Repo repo, final Pkg pkg) throws Exception {
    return this.protocol(
            get(
                    NUSPEC_PATH,
                    repo.getName(),
                    pkg.id().toLowerCase(Locale.ROOT),
                    pkg.version(),
                    pkg.id().toLowerCase(Locale.ROOT),
                    pkg.version())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Nested
  @DisplayName("a package pushed once")
  class HappyPath {

    @Test
    @DisplayName(
        "the registration leaf carries the metadata, dependency groups, listed flag and "
            + "package content URL of the pushed version")
    void registrationLeaf() throws Exception {
      final var repo = NuGetReadBackProtocolIT.this.nugetRepo();
      final var pkg =
          new Pkg(
              uniquePackageId(),
              "1.2.3",
              "leaf",
              dependencyOn("Newtonsoft.Json", "13.0.3", "net8.0"));
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();
      final var lowerId = pkg.id().toLowerCase(Locale.ROOT);

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      NuGetReadBackProtocolIT.this
          .readRegistrationLeaf(repo, pkg)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.catalogEntry.id").value(lowerId))
          .andExpect(jsonPath("$.catalogEntry.version").value(pkg.version()))
          .andExpect(jsonPath("$.catalogEntry.title").value(pkg.title()))
          .andExpect(jsonPath("$.catalogEntry.authors").value(pkg.authors()))
          .andExpect(jsonPath("$.catalogEntry.description").value(pkg.description()))
          .andExpect(jsonPath("$.catalogEntry.tags").value(pkg.tags()))
          .andExpect(jsonPath("$.catalogEntry.listed").value(true))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups", hasSize(1)))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups[0].targetFramework").value("net8.0"))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups[0].dependencies", hasSize(1)))
          .andExpect(
              jsonPath("$.catalogEntry.dependencyGroups[0].dependencies[0].id")
                  .value("Newtonsoft.Json"))
          .andExpect(
              jsonPath("$.catalogEntry.dependencyGroups[0].dependencies[0].range").value("13.0.3"))
          .andExpect(jsonPath("$.listed").value(true))
          .andExpect(
              jsonPath("$.packageContent")
                  .value(
                      endsWith("/v3/package/" + lowerId + "/1.2.3/" + lowerId + ".1.2.3.nupkg")));
    }

    @Test
    @DisplayName("the flat-container version list includes the pushed version")
    void versionList() throws Exception {
      final var repo = NuGetReadBackProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "2.0.0", "versions");
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      NuGetReadBackProtocolIT.this
          .readVersionList(repo, pkg)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.versions", hasSize(1)))
          .andExpect(jsonPath("$.versions[0]").value("2.0.0"));
    }

    @Test
    @DisplayName("the .nupkg download returns exactly the bytes that were pushed")
    void nupkgDownload() throws Exception {
      final var repo = NuGetReadBackProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0", "nupkg");
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();
      final var nupkg = pkg.nupkg();

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, nupkg, token), 201);

      assertThat(NuGetReadBackProtocolIT.this.downloadNupkg(repo, pkg)).isEqualTo(nupkg);
    }

    @Test
    @DisplayName("the .nuspec download returns exactly the nuspec that was pushed")
    void nuspecDownload() throws Exception {
      final var repo = NuGetReadBackProtocolIT.this.nugetRepo();
      final var pkg = new Pkg(uniquePackageId(), "1.0.0", "nuspec");
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, pkg.nupkg(), token), 201);

      assertThat(NuGetReadBackProtocolIT.this.downloadNuspec(repo, pkg)).isEqualTo(pkg.nuspec());
    }
  }

  @Nested
  @DisplayName("a version overridden in a repo that allows it (RPS-948)")
  class OverrideScenario {

    @Test
    @DisplayName(
        "the registration leaf, the .nupkg and the .nuspec downloads reflect the replacement "
            + "package, including one that drops the dependencies the first push declared")
    void readEndpointsReflectTheReplacement() throws Exception {
      final var repo = NuGetReadBackProtocolIT.this.nugetRepo();
      final var id = uniquePackageId();
      final var first =
          new Pkg(id, "1.0.0", "first", dependencyOn("Newtonsoft.Json", "13.0.3", "net8.0"));
      final var second = new Pkg(id, "1.0.0", "second");
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();
      final var lowerId = id.toLowerCase(Locale.ROOT);

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, first.nupkg(), token), 201);
      NuGetReadBackProtocolIT.this
          .readRegistrationLeaf(repo, first)
          .andExpect(jsonPath("$.catalogEntry.title").value(first.title()))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups", hasSize(1)));

      final var replacement = second.nupkg();
      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, replacement, token), 201);

      NuGetReadBackProtocolIT.this
          .readRegistrationLeaf(repo, second)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.catalogEntry.id").value(lowerId))
          .andExpect(jsonPath("$.catalogEntry.title").value(second.title()))
          .andExpect(jsonPath("$.catalogEntry.authors").value(second.authors()))
          .andExpect(jsonPath("$.catalogEntry.description").value(second.description()))
          .andExpect(jsonPath("$.catalogEntry.tags").value(second.tags()))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups").doesNotExist());

      NuGetReadBackProtocolIT.this
          .readVersionList(repo, second)
          .andExpect(jsonPath("$.versions", hasSize(1)))
          .andExpect(jsonPath("$.versions[0]").value("1.0.0"));

      assertThat(NuGetReadBackProtocolIT.this.downloadNupkg(repo, second)).isEqualTo(replacement);
      assertThat(NuGetReadBackProtocolIT.this.downloadNuspec(repo, second))
          .isEqualTo(second.nuspec());
    }
  }

  @Nested
  @DisplayName("a duplicate the repo rejects")
  class RejectedDuplicate {

    @Test
    @DisplayName(
        "leaves the registration leaf and the downloads exactly as the first push left them")
    void readEndpointsAreUnchanged() throws Exception {
      final var created = NuGetReadBackProtocolIT.this.nugetRepo();
      final var repo = NuGetReadBackProtocolIT.this.withOverrideDisabled(created);
      final var id = uniquePackageId();
      final var first =
          new Pkg(id, "1.0.0", "kept", dependencyOn("Serilog", "3.1.1", ".NETStandard2.0"));
      final var second = new Pkg(id, "1.0.0", "rejected");
      final var token = NuGetReadBackProtocolIT.this.adminProtocolBearerToken();
      final var original = first.nupkg();

      assertStatus(NuGetReadBackProtocolIT.this.pushAs(repo, original, token), 201);

      NuGetReadBackProtocolIT.this
          .protocol(
              multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
                  .part(new MockPart(PACKAGE_PART, "package.nupkg", second.nupkg()))
                  .header(AUTHORIZATION, token))
          .andExpect(status().isConflict());

      NuGetReadBackProtocolIT.this
          .readRegistrationLeaf(repo, first)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.catalogEntry.title").value(first.title()))
          .andExpect(jsonPath("$.catalogEntry.dependencyGroups", hasSize(1)))
          .andExpect(
              jsonPath("$.catalogEntry.dependencyGroups[0].dependencies[0].id").value("Serilog"));

      NuGetReadBackProtocolIT.this
          .readVersionList(repo, first)
          .andExpect(jsonPath("$.versions", hasSize(1)))
          .andExpect(jsonPath("$.versions[0]").value("1.0.0"));

      assertThat(NuGetReadBackProtocolIT.this.downloadNupkg(repo, first)).isEqualTo(original);
      assertThat(NuGetReadBackProtocolIT.this.downloadNuspec(repo, first))
          .isEqualTo(first.nuspec());
    }
  }
}
