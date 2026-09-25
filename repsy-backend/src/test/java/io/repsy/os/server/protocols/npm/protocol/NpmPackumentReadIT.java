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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MODIFIED_SINCE;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.HttpHeaders.LAST_MODIFIED;
import static org.springframework.http.HttpHeaders.VARY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What the npm wire protocol reads: the abbreviated packument (RPS-1356), the packument without
 * what a publish leaves in it (RPS-1357), HEAD (RPS-1358), the validators of the packument
 * (RPS-1359), undeprecating (RPS-1360) and the header of a tarball download (RPS-1363). Every
 * request goes through the real router, pre-processor and handlers.
 */
@DisplayName("npm wire protocol reads: packument, HEAD and tarball")
@SuppressWarnings("unchecked")
class NpmPackumentReadIT extends AbstractIntegrationTest {

  private static final String PACKAGE_PATH = "/{repo}/{packagePath}";
  private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";
  private static final String PNPM_ACCEPT = ABBREVIATED + "; q=1.0, application/json; q=0.8";
  private static final byte[] TARBALL = "tarball bytes".getBytes(StandardCharsets.UTF_8);

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private Repo npmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("read"));
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /**
   * An {@code npm publish} body: one version, its tarball as an attachment, and the fields npm adds
   * that are its own (the publisher's paths).
   */
  private byte[] publishBody(
      final Repo repo,
      final String packageName,
      final String version,
      final String tag,
      final Map<String, Object> versionExtra) {

    final var bare = packageName.substring(packageName.indexOf('/') + 1);
    final var fileName = bare + "-" + version + ".tgz";

    final var versionMetadata = new LinkedHashMap<String, Object>(versionExtra);
    versionMetadata.put("name", packageName);
    versionMetadata.put("version", version);
    versionMetadata.put(
        "dist",
        Map.of(
            "tarball",
            "http://localhost:9090/" + repo.getName() + "/" + packageName + "/-/" + fileName));

    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", packageName);
    body.put("name", packageName);
    body.put("dist-tags", Map.of(tag, version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put("_from", "file:/home/ada/" + fileName);
    body.put("_resolved", "/home/ada/" + fileName);
    body.put(
        "_attachments",
        Map.of(
            fileName,
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(TARBALL),
                "length",
                TARBALL.length)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  private void publish(
      final Repo repo,
      final String packageName,
      final String version,
      final String tag,
      final Map<String, Object> versionExtra,
      final String token)
      throws Exception {

    final var extra = new LinkedHashMap<String, Object>(versionExtra);
    extra.put("_from", "file:/home/ada/demo.tgz");
    extra.put("_resolved", "/home/ada/demo.tgz");

    final var response =
        this.protocol(
            put(PACKAGE_PATH, repo.getName(), packageName)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.publishBody(repo, packageName, version, tag, extra)));

    assertThat(response.getStatus()).as("publish %s@%s", packageName, version).isEqualTo(200);
  }

  private MockHttpServletResponse getPackument(
      final Repo repo, final String packageName, final String accept, final String token)
      throws Exception {

    return this.protocol(
        get(PACKAGE_PATH, repo.getName(), packageName)
            .header(AUTHORIZATION, token)
            .header(ACCEPT, accept));
  }

  private Map<String, Object> read(final MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(200);

    return this.objectMapper.readValue(
        response.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  private static Map<String, Object> versionOf(
      final Map<String, Object> packument, final String version) {

    return (Map<String, Object>) ((Map<String, Object>) packument.get("versions")).get(version);
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1356
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("the abbreviated packument (RPS-1356)")
  class Abbreviated {

    @Test
    @DisplayName(
        "carries os, cpu, libc, peerDependenciesMeta and funding of a version, like the full one")
    void carriesTheInstallFields() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      final var extra = new LinkedHashMap<String, Object>();
      extra.put("os", java.util.List.of("linux", "!win32"));
      extra.put("cpu", java.util.List.of("x64"));
      extra.put("libc", java.util.List.of("glibc"));
      extra.put("peerDependenciesMeta", Map.of("react", Map.of("optional", true)));
      extra.put("funding", Map.of("url", "https://example.test/fund"));
      NpmPackumentReadIT.this.publish(repo, "abbr", "1.0.0", "latest", extra, token);

      final var abbreviated =
          NpmPackumentReadIT.this.read(
              NpmPackumentReadIT.this.getPackument(repo, "abbr", PNPM_ACCEPT, token));
      final var full =
          NpmPackumentReadIT.this.read(
              NpmPackumentReadIT.this.getPackument(repo, "abbr", "application/json", token));

      for (final var served : java.util.List.of(abbreviated, full)) {
        assertThat(versionOf(served, "1.0.0"))
            .containsEntry("os", java.util.List.of("linux", "!win32"))
            .containsEntry("cpu", java.util.List.of("x64"))
            .containsEntry("libc", java.util.List.of("glibc"))
            .containsEntry("peerDependenciesMeta", Map.of("react", Map.of("optional", true)))
            .containsEntry("funding", Map.of("url", "https://example.test/fund"));
      }
      assertThat(abbreviated).doesNotContainKey("readme").containsKey("modified");
    }
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1357
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("what a publish leaves out of the packument (RPS-1357)")
  class PublishOnlyFields {

    @Test
    @DisplayName(
        "neither the served nor the stored packument keeps _attachments, _from or _resolved")
    void keepsNone() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();

      // The first publish creates the package, the second replaces the packument with the client's
      // own (latest), the third goes through the tag path (--tag next).
      NpmPackumentReadIT.this.publish(repo, "clean", "1.0.0", "latest", Map.of(), token);
      NpmPackumentReadIT.this.publish(repo, "clean", "1.1.0", "latest", Map.of(), token);
      NpmPackumentReadIT.this.publish(repo, "clean", "2.0.0-beta.1", "next", Map.of(), token);

      final var served =
          NpmPackumentReadIT.this.read(
              NpmPackumentReadIT.this.getPackument(repo, "clean", "application/json", token));
      final var stored =
          NpmPackumentReadIT.this.objectMapper.readValue(
              Files.readAllBytes(storageDirOf(repo).resolve("clean").resolve("package.json")),
              new TypeReference<Map<String, Object>>() {});

      assertThat(((Map<String, Object>) served.get("versions")).keySet())
          .containsExactly("1.0.0", "1.1.0", "2.0.0-beta.1");
      for (final var packument : java.util.List.of(served, stored)) {
        assertThat(packument).doesNotContainKeys("_attachments", "_from", "_resolved");

        for (final var version : ((Map<String, Object>) packument.get("versions")).values()) {
          assertThat((Map<String, Object>) version).doesNotContainKeys("_from", "_resolved");
        }
      }
    }

    @Test
    @DisplayName("a packument that an earlier version stored with them is served without them")
    void servesLegacyWithoutThem() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "legacy", "1.0.0", "latest", Map.of(), token);

      final var file = storageDirOf(repo).resolve("legacy").resolve("package.json");
      final var stored =
          NpmPackumentReadIT.this.objectMapper.readValue(
              Files.readAllBytes(file), new TypeReference<Map<String, Object>>() {});
      stored.put("_attachments", Map.of("legacy-1.0.0.tgz", Map.of("data", "AAAA")));
      stored.put("_resolved", "/home/ada/legacy-1.0.0.tgz");
      versionOf(stored, "1.0.0").put("_resolved", "/home/ada/legacy-1.0.0.tgz");
      Files.write(file, NpmPackumentReadIT.this.objectMapper.writeValueAsBytes(stored));

      for (final var accept : java.util.List.of("application/json", PNPM_ACCEPT)) {
        final var served =
            NpmPackumentReadIT.this.read(
                NpmPackumentReadIT.this.getPackument(repo, "legacy", accept, token));

        assertThat(served).doesNotContainKeys("_attachments", "_resolved");
        assertThat(versionOf(served, "1.0.0")).doesNotContainKey("_resolved");
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1358
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("HEAD (RPS-1358)")
  class Head {

    private int headStatus(final Repo repo, final String path, final String token)
        throws Exception {
      return NpmPackumentReadIT.this
          .protocol(head("/{repo}/" + path, repo.getName()).header(AUTHORIZATION, token))
          .getStatus();
    }

    @Test
    @DisplayName("is 200 for a package that exists and 404 for one that does not, like GET")
    void packageExistence() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "there", "1.0.0", "latest", Map.of(), token);

      assertThat(this.headStatus(repo, "there", token)).isEqualTo(200);
      assertThat(this.headStatus(repo, "never-published", token)).isEqualTo(404);
      assertThat(
              NpmPackumentReadIT.this
                  .getPackument(repo, "never-published", "application/json", token)
                  .getStatus())
          .as("GET of the same path")
          .isEqualTo(404);
    }

    @Test
    @DisplayName("is 200 for a scoped package that exists and 404 for one that does not")
    void scopedPackageExistence() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "@acme/there", "1.0.0", "latest", Map.of(), token);

      assertThat(this.headStatus(repo, "@acme/there", token)).isEqualTo(200);
      assertThat(this.headStatus(repo, "@acme/never-published", token)).isEqualTo(404);
      assertThat(this.headStatus(repo, "@other/there", token)).isEqualTo(404);
    }

    @Test
    @DisplayName("is 200 for a tarball that exists and 404 for one that does not")
    void tarballExistence() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "there", "1.0.0", "latest", Map.of(), token);
      NpmPackumentReadIT.this.publish(repo, "@acme/there", "1.0.0", "latest", Map.of(), token);

      assertThat(this.headStatus(repo, "there/-/there-1.0.0.tgz", token)).isEqualTo(200);
      assertThat(this.headStatus(repo, "there/-/there-9.9.9.tgz", token)).isEqualTo(404);
      assertThat(this.headStatus(repo, "never-published/-/never-published-1.0.0.tgz", token))
          .isEqualTo(404);
      assertThat(this.headStatus(repo, "@acme/there/-/there-1.0.0.tgz", token)).isEqualTo(200);
      assertThat(this.headStatus(repo, "@acme/there/-/there-9.9.9.tgz", token)).isEqualTo(404);
    }

    @Test
    @DisplayName("still answers 200 for the repo and the registry endpoints below /-/")
    void registryEndpoints() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();

      assertThat(this.headStatus(repo, "-/ping", token)).isEqualTo(200);
      assertThat(this.headStatus(repo, "-/whoami", token)).isEqualTo(200);
    }

    @Test
    @DisplayName(
        "is 404 in a repository that does not exist, and asks for credentials of a private one")
    void repositories() throws Exception {
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      final var privateRepo =
          NpmPackumentReadIT.this.seedRepo(RepoType.NPM, uniqueRepoName("read"), true, null);

      assertThat(
              NpmPackumentReadIT.this
                  .protocol(
                      head("/{repo}/x", uniqueRepoName("no-repo")).header(AUTHORIZATION, token))
                  .getStatus())
          .isEqualTo(404);
      assertThat(
              NpmPackumentReadIT.this
                  .protocol(head("/{repo}/never-published", privateRepo.getName()))
                  .getStatus())
          .as("a private repo tells nothing to a request without credentials")
          .isEqualTo(401);
    }
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1359
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("the validators of the packument (RPS-1359)")
  class Validators {

    private MockHttpServletResponse conditional(
        final Repo repo,
        final String accept,
        final String token,
        final String header,
        final String value)
        throws Exception {

      return NpmPackumentReadIT.this.protocol(
          get(PACKAGE_PATH, repo.getName(), "cached")
              .header(AUTHORIZATION, token)
              .header(ACCEPT, accept)
              .header(header, value));
    }

    @Test
    @DisplayName("the packument has a weak ETag, a Last-Modified and Vary: Accept")
    void hasValidators() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "cached", "1.0.0", "latest", Map.of(), token);

      final var full =
          NpmPackumentReadIT.this.getPackument(repo, "cached", "application/json", token);
      final var abbreviated =
          NpmPackumentReadIT.this.getPackument(repo, "cached", PNPM_ACCEPT, token);

      assertThat(full.getHeader(ETAG)).matches("W/\"[0-9a-f]{64}\"");
      assertThat(full.getHeader(LAST_MODIFIED)).isNotBlank();
      assertThat(full.getHeaders(VARY)).contains("Accept");
      assertThat(full.getContentType()).isEqualTo("application/json");
      assertThat(abbreviated.getHeader(ETAG)).matches("W/\"[0-9a-f]{64}\"");
      assertThat(abbreviated.getHeaders(VARY)).contains("Accept");
      assertThat(abbreviated.getContentType()).startsWith(ABBREVIATED);
      assertThat(abbreviated.getHeader(ETAG))
          .as("the two documents of one address have a tag each")
          .isNotEqualTo(full.getHeader(ETAG));
      assertThat(
              NpmPackumentReadIT.this
                  .getPackument(repo, "cached", "application/json", token)
                  .getHeader(ETAG))
          .as("the tag is stable while nothing changes")
          .isEqualTo(full.getHeader(ETAG));
    }

    @Test
    @DisplayName("a client that holds the current document is answered 304, with no body")
    void notModified() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "cached", "1.0.0", "latest", Map.of(), token);

      for (final var accept : java.util.List.of("application/json", PNPM_ACCEPT)) {
        final var first = NpmPackumentReadIT.this.getPackument(repo, "cached", accept, token);

        final var byTag =
            this.conditional(repo, accept, token, IF_NONE_MATCH, first.getHeader(ETAG));
        assertThat(byTag.getStatus()).as("If-None-Match, %s", accept).isEqualTo(304);
        assertThat(byTag.getContentAsByteArray()).isEmpty();
        assertThat(byTag.getHeader(ETAG)).isEqualTo(first.getHeader(ETAG));

        final var byDate =
            this.conditional(
                repo, accept, token, IF_MODIFIED_SINCE, first.getHeader(LAST_MODIFIED));
        assertThat(byDate.getStatus()).as("If-Modified-Since, %s", accept).isEqualTo(304);
      }
    }

    @Test
    @DisplayName(
        "the tag of the other document, a stale tag and a changed package get the document")
    void modified() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "cached", "1.0.0", "latest", Map.of(), token);
      final var full =
          NpmPackumentReadIT.this.getPackument(repo, "cached", "application/json", token);
      final var abbreviated =
          NpmPackumentReadIT.this.getPackument(repo, "cached", PNPM_ACCEPT, token);

      assertThat(
              this.conditional(repo, PNPM_ACCEPT, token, IF_NONE_MATCH, full.getHeader(ETAG))
                  .getStatus())
          .as("the abbreviated document is not the full one")
          .isEqualTo(200);
      assertThat(
              this.conditional(repo, "application/json", token, IF_NONE_MATCH, "\"stale\"")
                  .getStatus())
          .isEqualTo(200);

      NpmPackumentReadIT.this.publish(repo, "cached", "1.1.0", "latest", Map.of(), token);

      final var changed =
          this.conditional(repo, "application/json", token, IF_NONE_MATCH, full.getHeader(ETAG));
      assertThat(changed.getStatus()).as("a version was published since").isEqualTo(200);
      assertThat(changed.getHeader(ETAG)).isNotEqualTo(full.getHeader(ETAG));
      assertThat(
              this.conditional(repo, PNPM_ACCEPT, token, IF_NONE_MATCH, abbreviated.getHeader(ETAG))
                  .getStatus())
          .isEqualTo(200);
    }
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1360
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("undeprecating a version (RPS-1360)")
  class Undeprecate {

    /** What {@code npm deprecate pkg@x <message>} sends: the packument it read, edited. */
    private void deprecate(
        final Repo repo,
        final String name,
        final String version,
        final String message,
        final String token)
        throws Exception {

      final var packument =
          NpmPackumentReadIT.this.read(
              NpmPackumentReadIT.this.getPackument(repo, name, "application/json", token));
      versionOf(packument, version).put("deprecated", message);

      final var response =
          NpmPackumentReadIT.this.protocol(
              put(PACKAGE_PATH, repo.getName(), name)
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(NpmPackumentReadIT.this.objectMapper.writeValueAsBytes(packument)));

      assertThat(response.getStatus()).as("deprecate %s@%s", name, version).isEqualTo(200);
    }

    @Test
    @DisplayName(
        "removes the deprecated of the version from the full and the abbreviated packument")
    void removesTheField() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "dep", "1.0.0", "latest", Map.of(), token);
      NpmPackumentReadIT.this.publish(repo, "dep", "1.1.0", "latest", Map.of(), token);

      this.deprecate(repo, "dep", "1.0.0", "use 1.1.0", token);
      this.deprecate(repo, "dep", "1.1.0", "use 1.2.0", token);

      for (final var accept : java.util.List.of("application/json", PNPM_ACCEPT)) {
        final var served =
            NpmPackumentReadIT.this.read(
                NpmPackumentReadIT.this.getPackument(repo, "dep", accept, token));

        assertThat(versionOf(served, "1.0.0")).containsEntry("deprecated", "use 1.1.0");
        assertThat(versionOf(served, "1.1.0")).containsEntry("deprecated", "use 1.2.0");
      }

      this.deprecate(repo, "dep", "1.0.0", "", token);

      for (final var accept : java.util.List.of("application/json", PNPM_ACCEPT)) {
        final var served =
            NpmPackumentReadIT.this.read(
                NpmPackumentReadIT.this.getPackument(repo, "dep", accept, token));

        assertThat(versionOf(served, "1.0.0"))
            .as("undeprecated, %s", accept)
            .doesNotContainKey("deprecated");
        assertThat(versionOf(served, "1.1.0")).containsEntry("deprecated", "use 1.2.0");
      }

      final var stored =
          NpmPackumentReadIT.this.objectMapper.readValue(
              Files.readAllBytes(storageDirOf(repo).resolve("dep").resolve("package.json")),
              new TypeReference<Map<String, Object>>() {});
      assertThat(versionOf(stored, "1.0.0")).doesNotContainKey("deprecated");

      assertThat(
              NpmPackumentReadIT.this.jdbcTemplate.queryForList(
                  """
                  select v.version, v.deprecated, v.deprecation_message from npm_package_version v
                    join npm_package p on p.id = v.package_id
                  where p.repo_id = ? and p.name = 'dep' order by v.version
                  """,
                  repo.getId()))
          .as("the rows the panel reads")
          .extracting(row -> row.get("deprecated"), row -> row.get("deprecation_message"))
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(false, null),
              org.assertj.core.groups.Tuple.tuple(true, "use 1.2.0"));
    }

    @Test
    @DisplayName("an empty deprecated that an earlier version stored is not served")
    void doesNotServeAStoredEmptyField() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "legacy", "1.0.0", "latest", Map.of(), token);

      final var file = storageDirOf(repo).resolve("legacy").resolve("package.json");
      final var stored =
          NpmPackumentReadIT.this.objectMapper.readValue(
              Files.readAllBytes(file), new TypeReference<Map<String, Object>>() {});
      versionOf(stored, "1.0.0").put("deprecated", "");
      Files.write(file, NpmPackumentReadIT.this.objectMapper.writeValueAsBytes(stored));

      for (final var accept : java.util.List.of("application/json", PNPM_ACCEPT)) {
        assertThat(
                versionOf(
                    NpmPackumentReadIT.this.read(
                        NpmPackumentReadIT.this.getPackument(repo, "legacy", accept, token)),
                    "1.0.0"))
            .doesNotContainKey("deprecated");
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1363
  // -------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("the tarball download (RPS-1363)")
  class TarballDownload {

    @Test
    @DisplayName("is offered as an attachment named after the tarball, not \"f.txt\" inline")
    void namesTheTarball() throws Exception {
      final var repo = NpmPackumentReadIT.this.npmRepo();
      final var token = NpmPackumentReadIT.this.adminProtocolBearerToken();
      NpmPackumentReadIT.this.publish(repo, "dl", "1.0.0", "latest", Map.of(), token);
      NpmPackumentReadIT.this.publish(repo, "@acme/dl", "1.0.0", "latest", Map.of(), token);

      final var unscoped =
          NpmPackumentReadIT.this.protocol(
              get("/{repo}/dl/-/dl-1.0.0.tgz", repo.getName()).header(AUTHORIZATION, token));
      final var scoped =
          NpmPackumentReadIT.this.protocol(
              get("/{repo}/@acme/dl/-/dl-1.0.0.tgz", repo.getName()).header(AUTHORIZATION, token));

      assertThat(unscoped.getStatus()).isEqualTo(200);
      assertThat(unscoped.getContentAsByteArray()).isEqualTo(TARBALL);
      assertThat(unscoped.getContentType()).isEqualTo("application/octet-stream");
      assertThat(unscoped.getHeader(CONTENT_DISPOSITION))
          .isEqualTo("attachment; filename=\"dl-1.0.0.tgz\"");
      assertThat(scoped.getStatus()).isEqualTo(200);
      assertThat(scoped.getHeader(CONTENT_DISPOSITION))
          .isEqualTo("attachment; filename=\"dl-1.0.0.tgz\"");
    }
  }
}
