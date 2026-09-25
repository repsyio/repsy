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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack coverage for the npm wire-protocol publish path ({@code PUT /{repo}/{package}}),
 * pinning two backend bugs together because both live in the same publish flow and the same {@code
 * PackageUtils}:
 *
 * <ul>
 *   <li><b>RPS-1205</b> — {@code fixTarballUrl} used to assume a cloud multi-tenant URL shape (a
 *       {@code <tenant>} segment Repsy OS does not have) and spliced the repo name into the middle
 *       of the package path, corrupting the packument's {@code dist.tarball}. A real npm client
 *       trusts that URL for the download, so it 404'd even though the tarball was stored correctly
 *       and servable at its canonical path.
 *   <li><b>RPS-1211</b> — redeploying an already-published version without a {@code keywords} field
 *       threw a {@code ClassCastException} ({@code liftFieldsToTopLevel} defaulted it to a {@code
 *       String[]}, and {@code addKeywords} cast straight to {@code ArrayList<String>}), which the
 *       facade's generic {@code catch (ClassCastException ...)} turned into an opaque {@code 400}.
 * </ul>
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async} and cannot see this test's
 * uncommitted data. Every test runs in the default rolled-back transaction (see {@link
 * AbstractIntegrationTest}); npm's package/version writes use ordinary {@code REQUIRED}
 * propagation, so a repo seeded through {@link #seedRepo} in the same test is visible to the
 * publish request without needing a committed fixture.
 */
@DisplayName("npm wire protocol PUT /{repo}/{package}")
class NpmPublishProtocolIT extends AbstractIntegrationTest {

  private static final String HOST = "http://localhost:9090";
  private static final String PUBLISH_PATH = "/{repo}/{packagePath}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;
  @Autowired private PackageKeywordRepository packageKeywordRepository;
  @Autowired private PackageMaintainerRepository packageMaintainerRepository;

  private Repo npmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc
        .perform(request.with(protocolPort()).with(seenAt("http", "localhost", 9090)))
        .andReturn()
        .getResponse();
  }

  /**
   * The address a client reached the server at, as Tomcat reports it once it has applied the
   * forwarded headers: MockMvc does not run the servlet container's valves.
   */
  private static RequestPostProcessor seenAt(
      final String scheme, final String host, final int port) {
    return request -> {
      request.setScheme(scheme);
      request.setServerName(host);
      request.setServerPort(port);
      return request;
    };
  }

  /** Builds an npm publish body the way a real client would: one version, one tarball. */
  private byte[] publishBody(
      final String packageName,
      final String version,
      final String tarballUrl,
      final byte[] tarballBytes,
      final @Nullable List<String> keywords,
      final @Nullable List<Map<String, String>> maintainers) {

    final var dist = new LinkedHashMap<String, Object>();
    dist.put("tarball", tarballUrl);

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", packageName);
    versionMetadata.put("version", version);
    versionMetadata.put("dist", dist);
    if (keywords != null) {
      versionMetadata.put("keywords", keywords);
    }
    if (maintainers != null) {
      versionMetadata.put("maintainers", maintainers);
    }

    final var attachmentName = packageName.replace("/", "-") + "-" + version + ".tgz";
    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", packageName);
    body.put("name", packageName);
    body.put("dist-tags", Map.of("latest", version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            attachmentName,
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarballBytes),
                "length",
                tarballBytes.length)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  private MockHttpServletResponse publish(
      final Repo repo,
      final String packagePath,
      final String packageName,
      final String version,
      final String tarballUrl,
      final byte[] tarballBytes,
      final @Nullable List<String> keywords,
      final @Nullable List<Map<String, String>> maintainers)
      throws Exception {

    return this.protocol(
        put(PUBLISH_PATH, repo.getName(), packagePath)
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                this.publishBody(
                    packageName, version, tarballUrl, tarballBytes, keywords, maintainers)));
  }

  /**
   * Builds a publish body giving full control over the top-level {@code name}/{@code _id}, the
   * dist-tags map and the single version's own fields, for the RPS-1207 and RPS-1136 tests below,
   * which need a body that a real client would never send.
   */
  private byte[] rawPublishBody(
      final @Nullable String bodyName,
      final @Nullable String bodyId,
      final Map<String, String> distTags,
      final String versionName,
      final Map<String, Object> versionExtra) {

    final var dist = new LinkedHashMap<String, Object>();
    dist.put("tarball", "http://h:9090/repo/pkg/-/pkg-" + versionName + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>(versionExtra);
    versionMetadata.putIfAbsent("version", versionName);
    versionMetadata.put("dist", dist);

    final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
    final var body = new LinkedHashMap<String, Object>();
    if (bodyName != null) {
      body.put("name", bodyName);
    }
    if (bodyId != null) {
      body.put("_id", bodyId);
    }
    body.put("dist-tags", distTags);
    body.put("versions", Map.of(versionName, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            "pkg-" + versionName + ".tgz",
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarball),
                "length",
                tarball.length)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  private MockHttpServletResponse rawPublish(
      final Repo repo, final String packagePath, final byte[] body) throws Exception {

    return this.protocol(
        put(PUBLISH_PATH, repo.getName(), packagePath)
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> packument(final Repo repo, final String packagePath)
      throws Exception {
    final var response =
        this.protocol(
            get(PUBLISH_PATH, repo.getName(), packagePath)
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));
    assertThat(response.getStatus()).isEqualTo(200);

    return this.objectMapper.readValue(
        response.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private String tarballUrlOf(final Repo repo, final String packagePath, final String version)
      throws Exception {
    final var packument = this.packument(repo, packagePath);
    final var versions = (Map<String, Object>) packument.get("versions");
    final var versionMetadata = (Map<String, Object>) versions.get(version);
    final var dist = (Map<String, Object>) versionMetadata.get("dist");

    return (String) dist.get("tarball");
  }

  private MockHttpServletResponse download(final String tarballUrl) throws Exception {
    return this.protocol(
        get(URI.create(tarballUrl).getRawPath())
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private PackageVersion versionEntity(
      final Repo repo,
      final @Nullable String scope,
      final String packageName,
      final String version) {
    this.entityManager.flush();
    this.entityManager.clear();

    final var npmPackage =
        this.npmPackageRepository
            .findByRepoIdAndScopeAndName(repo.getId(), scope, packageName)
            .orElseThrow();

    return this.packageVersionRepository
        .findByNpmPackageIdAndVersion(npmPackage.getId(), version)
        .orElseThrow();
  }

  private List<String> keywordsOf(final Repo repo, final String packageName, final String version) {
    final var packageVersion = this.versionEntity(repo, null, packageName, version);

    return this.packageKeywordRepository.findAllByPackageVersionId(packageVersion.getId()).stream()
        .map(PackageKeywordListItem::getKeyword)
        .toList();
  }

  private List<String> maintainerNamesOf(
      final Repo repo, final String packageName, final String version) {
    final var packageVersion = this.versionEntity(repo, null, packageName, version);

    return this.packageMaintainerRepository
        .findAllByPackageVersionId(packageVersion.getId())
        .stream()
        .map(PackageMaintainerListItem::getName)
        .toList();
  }

  @Nested
  @DisplayName("RPS-1205: dist.tarball vs. the canonical stored path")
  class TarballUrl {

    @Test
    @DisplayName("an unscoped package's canonical tarball URL is left unchanged and is servable")
    void unscopedTarballServable() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1205-demo";
      final var canonicalUrl = HOST + "/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz";
      final var tarball = "unscoped tarball bytes".getBytes(StandardCharsets.UTF_8);

      final var response =
          NpmPublishProtocolIT.this.publish(
              repo, pkg, pkg, "1.0.0", canonicalUrl, tarball, null, null);
      assertThat(response.getStatus()).isEqualTo(200);

      final var tarballUrl = NpmPublishProtocolIT.this.tarballUrlOf(repo, pkg, "1.0.0");
      assertThat(tarballUrl).isEqualTo(canonicalUrl);

      final var downloaded = NpmPublishProtocolIT.this.download(tarballUrl);
      assertThat(downloaded.getStatus()).isEqualTo(200);
      assertThat(downloaded.getContentAsByteArray()).isEqualTo(tarball);
    }

    @Test
    @DisplayName("a scoped package's canonical tarball URL is left unchanged and is servable")
    void scopedTarballServable() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var scope = "rps1205";
      final var name = "scoped-demo";
      final var packageName = "@" + scope + "/" + name;
      final var canonicalUrl =
          HOST + "/" + repo.getName() + "/@" + scope + "/" + name + "/-/" + name + "-1.0.0.tgz";
      final var tarball = "scoped tarball bytes".getBytes(StandardCharsets.UTF_8);

      final var response =
          NpmPublishProtocolIT.this.publish(
              repo, packageName, packageName, "1.0.0", canonicalUrl, tarball, null, null);
      assertThat(response.getStatus()).isEqualTo(200);

      final var tarballUrl = NpmPublishProtocolIT.this.tarballUrlOf(repo, packageName, "1.0.0");
      assertThat(tarballUrl).isEqualTo(canonicalUrl);

      final var downloaded = NpmPublishProtocolIT.this.download(tarballUrl);
      assertThat(downloaded.getStatus()).isEqualTo(200);
      assertThat(downloaded.getContentAsByteArray()).isEqualTo(tarball);
    }

    @Test
    @DisplayName("a second version of an existing package (processVersionPayload) is also servable")
    void secondVersionTarballServable() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1205-second";
      final var firstUrl = HOST + "/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz";
      final var secondUrl = HOST + "/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.1.0.tgz";
      final var firstTarball = "first version bytes".getBytes(StandardCharsets.UTF_8);
      final var secondTarball = "second version bytes".getBytes(StandardCharsets.UTF_8);

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", firstUrl, firstTarball, null, null)
                  .getStatus())
          .isEqualTo(200);
      // The first publish goes through processPackagePayload (addPackage); this second one, for
      // an already-existing package, goes through processVersionPayload instead — the other call
      // site of fixTarballUrl.
      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.1.0", secondUrl, secondTarball, null, null)
                  .getStatus())
          .isEqualTo(200);

      final var tarballUrl = NpmPublishProtocolIT.this.tarballUrlOf(repo, pkg, "1.1.0");
      assertThat(tarballUrl).isEqualTo(secondUrl);

      final var downloaded = NpmPublishProtocolIT.this.download(tarballUrl);
      assertThat(downloaded.getStatus()).isEqualTo(200);
      assertThat(downloaded.getContentAsByteArray()).isEqualTo(secondTarball);
    }
  }

  @Nested
  @DisplayName("RPS-1333: dist.tarball is the address the registry is reached at")
  class RegistryTarballUrl {

    private static final String NPM_ABBREVIATED = "application/vnd.npm.install-v1+json";

    private MockHttpServletResponse at(
        final AbstractMockHttpServletRequestBuilder<?> request,
        final String scheme,
        final String host,
        final int port)
        throws Exception {

      return NpmPublishProtocolIT.this
          .mockMvc
          .perform(
              request
                  .header(AUTHORIZATION, NpmPublishProtocolIT.this.adminProtocolBearerToken())
                  .with(protocolPort())
                  .with(seenAt(scheme, host, port)))
          .andReturn()
          .getResponse();
    }

    @SuppressWarnings("unchecked")
    private String tarballOf(final MockHttpServletResponse response, final String version)
        throws Exception {
      assertThat(response.getStatus()).isEqualTo(200);

      final var packument =
          NpmPublishProtocolIT.this.objectMapper.readValue(
              response.getContentAsString(StandardCharsets.UTF_8),
              new TypeReference<Map<String, Object>>() {});
      final var versions = (Map<String, Object>) packument.get("versions");
      final var dist =
          (Map<String, Object>) ((Map<String, Object>) versions.get(version)).get("dist");

      return (String) dist.get("tarball");
    }

    private void publishAt(
        final Repo repo,
        final String packageName,
        final String version,
        final String clientsTarballUrl,
        final String scheme,
        final String host,
        final int port)
        throws Exception {

      final var response =
          this.at(
              put(PUBLISH_PATH, repo.getName(), packageName)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      NpmPublishProtocolIT.this.publishBody(
                          packageName,
                          version,
                          clientsTarballUrl,
                          "bytes".getBytes(StandardCharsets.UTF_8),
                          null,
                          null)),
              scheme,
              host,
              port);

      assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName(
        "a publish through another address is served under the reader's, full and abbreviated")
    void servedUnderTheReadersAddress() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1333-demo";

      // libnpmpublish writes http:// even for an HTTPS registry, and the publisher used an address
      // the reader cannot reach.
      this.publishAt(
          repo,
          pkg,
          "1.0.0",
          "http://publisher.internal:9090/"
              + repo.getName()
              + "/"
              + pkg
              + "/-/"
              + pkg
              + "-1.0.0.tgz",
          "https",
          "repo.example.test",
          443);

      final var expected =
          "https://repo.example.test/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz";
      final var full =
          this.at(get(PUBLISH_PATH, repo.getName(), pkg), "https", "repo.example.test", 443);
      final var abbreviated =
          this.at(
              get(PUBLISH_PATH, repo.getName(), pkg).header("Accept", NPM_ABBREVIATED),
              "https",
              "repo.example.test",
              443);

      assertThat(this.tarballOf(full, "1.0.0")).isEqualTo(expected);
      assertThat(this.tarballOf(abbreviated, "1.0.0")).isEqualTo(expected);

      final var elsewhere =
          this.at(get(PUBLISH_PATH, repo.getName(), pkg), "http", "localhost", 9090);

      assertThat(this.tarballOf(elsewhere, "1.0.0"))
          .as("read from another address, the same file names that one")
          .isEqualTo(
              "http://localhost:9090/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz");
    }

    @Test
    @DisplayName("every version of a package follows the reader, whichever address published it")
    void everyVersionFollowsTheReader() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1333-many";

      this.publishAt(
          repo,
          pkg,
          "1.0.0",
          "http://a.internal:9090/x/" + pkg + "/-/" + pkg + "-1.0.0.tgz",
          "http",
          "a.internal",
          9090);
      this.publishAt(
          repo,
          pkg,
          "1.1.0",
          "http://b.internal:9090/x/" + pkg + "/-/" + pkg + "-1.1.0.tgz",
          "http",
          "b.internal",
          9090);

      final var response =
          this.at(get(PUBLISH_PATH, repo.getName(), pkg), "https", "repo.example.test", 443);
      final var body = response.getContentAsString(StandardCharsets.UTF_8);

      assertThat(body)
          .contains(
              "https://repo.example.test/"
                  + repo.getName()
                  + "/"
                  + pkg
                  + "/-/"
                  + pkg
                  + "-1.0.0.tgz",
              "https://repo.example.test/"
                  + repo.getName()
                  + "/"
                  + pkg
                  + "/-/"
                  + pkg
                  + "-1.1.0.tgz")
          .doesNotContain("a.internal", "b.internal");
    }

    @Test
    @DisplayName("a scoped package's URL has the scope in the path and none in the file name")
    void scopedPackage() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var packageName = "@rps1333/scoped";

      this.publishAt(
          repo,
          packageName,
          "1.0.0",
          "http://publisher.internal:9090/"
              + repo.getName()
              + "/@rps1333/scoped/-/@rps1333/scoped-1.0.0.tgz",
          "http",
          "publisher.internal",
          9090);

      final var response =
          this.at(
              get(PUBLISH_PATH, repo.getName(), packageName), "https", "repo.example.test", 443);
      final var tarballUrl = this.tarballOf(response, "1.0.0");

      assertThat(tarballUrl)
          .isEqualTo(
              "https://repo.example.test/"
                  + repo.getName()
                  + "/@rps1333/scoped/-/scoped-1.0.0.tgz");

      final var downloaded = NpmPublishProtocolIT.this.download(tarballUrl);

      assertThat(downloaded.getStatus()).isEqualTo(200);
      assertThat(downloaded.getContentAsByteArray())
          .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Nested
  @DisplayName("RPS-1211: redeploying a version without keywords")
  class RedeployWithoutKeywords {

    private String tarballUrl(final Repo repo, final String pkg, final String version) {
      return HOST + "/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-" + version + ".tgz";
    }

    @Test
    @DisplayName(
        "redeploying the same version without keywords answers 200, not the old 400 badRequest")
    void redeployWithoutKeywordsSucceeds() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo(); // allowOverride defaults to true
      final var pkg = "rps1211-demo";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url = this.tarballUrl(repo, pkg, "1.0.0");

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null)
                  .getStatus())
          .isEqualTo(200);

      // This is the regression: before the fix, this exact redeploy answered 400 badRequest.
      final var redeployResponse =
          NpmPublishProtocolIT.this.publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null);
      assertThat(redeployResponse.getStatus()).isEqualTo(200);

      // The packument still reads back, and no keyword rows exist for the version.
      assertThat(NpmPublishProtocolIT.this.packument(repo, pkg)).isNotEmpty();
      assertThat(NpmPublishProtocolIT.this.keywordsOf(repo, pkg, "1.0.0")).isEmpty();
    }

    @Test
    @DisplayName("a redeploy that adds keywords replaces them rather than duplicating them")
    void redeployReplacesKeywordsRatherThanDuplicating() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1211-keywords";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url = this.tarballUrl(repo, pkg, "1.0.0");

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, List.of("alpha", "beta"), null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.keywordsOf(repo, pkg, "1.0.0"))
          .containsExactlyInAnyOrder("alpha", "beta");

      // Redeploying again with a different keyword set replaces the rows, it does not add to them.
      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, List.of("gamma"), null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.keywordsOf(repo, pkg, "1.0.0"))
          .containsExactlyInAnyOrder("gamma");
    }

    @Test
    @DisplayName("redeploying without maintainers answers 200 and leaves no maintainer rows")
    void redeployWithoutMaintainersSucceeds() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1211-maintainers";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url = this.tarballUrl(repo, pkg, "1.0.0");

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.maintainerNamesOf(repo, pkg, "1.0.0")).isEmpty();
    }

    @Test
    @DisplayName("a redeploy that adds maintainers replaces them rather than duplicating them")
    void redeployReplacesMaintainersRatherThanDuplicating() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1211-maint2";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url = this.tarballUrl(repo, pkg, "1.0.0");
      final var first = List.of(Map.of("name", "first-maintainer", "email", "first@example.test"));
      final var second =
          List.of(Map.of("name", "second-maintainer", "email", "second@example.test"));

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, null)
                  .getStatus())
          .isEqualTo(200);
      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, first)
                  .getStatus())
          .isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.maintainerNamesOf(repo, pkg, "1.0.0"))
          .containsExactlyInAnyOrder("first-maintainer");

      assertThat(
              NpmPublishProtocolIT.this
                  .publish(repo, pkg, pkg, "1.0.0", url, tarball, null, second)
                  .getStatus())
          .isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.maintainerNamesOf(repo, pkg, "1.0.0"))
          .containsExactlyInAnyOrder("second-maintainer");
    }
  }

  @Nested
  @DisplayName("RPS-1207: publish body identity vs. the URL")
  class PackageNameMismatch {

    @Test
    @DisplayName("refuses a body whose top-level name does not match the URL's package")
    void refusesMismatchedName() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1207-name";
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              "not-" + pkg,
              pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageNameMismatch");
    }

    @Test
    @DisplayName("refuses a body whose _id does not match the URL's package")
    void refusesMismatchedId() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1207-id";
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              "not-" + pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageNameMismatch");
    }

    @Test
    @DisplayName("refuses a body whose versions[*].name does not match the URL's package")
    void refusesMismatchedVersionName() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1207-version-name";
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", "not-" + pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageNameMismatch");
    }

    @Test
    @DisplayName("accepts a body whose name, _id and versions[*].name all match the URL")
    void acceptsMatchingBody() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1207-matching";
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("RPS-1136: over-long publish metadata")
  class OverLongMetadata {

    private String repeat(final char c, final int length) {
      return String.valueOf(c).repeat(length);
    }

    @Test
    @DisplayName("refuses an over-long scope, naming the field")
    void refusesOverLongScope() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var scope = this.repeat('s', 215);
      final var pkg = "@" + scope + "/demo";
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageScopeTooLong");
    }

    @Test
    @DisplayName("refuses an over-long package name, naming the field")
    void refusesOverLongName() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = this.repeat('n', 215);
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              pkg,
              Map.of("latest", "1.0.0"),
              "1.0.0",
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageNameTooLong");
    }

    @Test
    @DisplayName("refuses an over-long but syntactically valid version, naming the field")
    void refusesOverLongVersion() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1136-version";
      // Syntactically valid semver (a long pre-release identifier), and over 128 characters.
      final var version = "1.0.0-" + this.repeat('a', 130);
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg,
              pkg,
              Map.of("latest", version),
              version,
              new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("packageVersionTooLong");
    }

    @Test
    @DisplayName("refuses an over-long dist-tag name, naming the field")
    void refusesOverLongDistTagName() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1136-dist-tag";
      final var tag = this.repeat('t', 256);
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg, pkg, Map.of(tag, "1.0.0"), "1.0.0", new LinkedHashMap<>(Map.of("name", pkg)));

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("distTagNameTooLong");
    }

    @Test
    @DisplayName("drops an over-long homepage silently, keeping the rest of the publish")
    void dropsOverLongHomepage() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1136-homepage";
      final var versionExtra = new LinkedHashMap<String, Object>();
      versionExtra.put("name", pkg);
      versionExtra.put("homepage", this.repeat('h', 256));
      final var body =
          NpmPublishProtocolIT.this.rawPublishBody(
              pkg, pkg, Map.of("latest", "1.0.0"), "1.0.0", versionExtra);

      final var response = NpmPublishProtocolIT.this.rawPublish(repo, pkg, body);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.versionEntity(repo, null, pkg, "1.0.0").getHomepage())
          .isNull();
    }

    @Test
    @DisplayName("drops only the over-long keyword, keeping the rest of the array")
    void dropsOnlyOverLongKeyword() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1136-keyword";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url =
          "http://localhost:9090/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz";
      final var longKeyword = this.repeat('k', 256);

      final var response =
          NpmPublishProtocolIT.this.publish(
              repo, pkg, pkg, "1.0.0", url, tarball, List.of("alpha", longKeyword, "beta"), null);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.keywordsOf(repo, pkg, "1.0.0"))
          .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("drops the whole maintainer entry when its name is over-long (NOT NULL column)")
    void dropsWholeMaintainerEntryForOverLongName() throws Exception {
      final var repo = NpmPublishProtocolIT.this.npmRepo();
      final var pkg = "rps1136-maintainer";
      final var tarball = "tarball bytes".getBytes(StandardCharsets.UTF_8);
      final var url =
          "http://localhost:9090/" + repo.getName() + "/" + pkg + "/-/" + pkg + "-1.0.0.tgz";
      final var maintainers =
          List.of(
              Map.of("name", "ok-maintainer"),
              Map.of("name", this.repeat('m', 256), "email", "x@example.test"));

      final var response =
          NpmPublishProtocolIT.this.publish(
              repo, pkg, pkg, "1.0.0", url, tarball, null, maintainers);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(NpmPublishProtocolIT.this.maintainerNamesOf(repo, pkg, "1.0.0"))
          .containsExactly("ok-maintainer");
    }
  }
}
