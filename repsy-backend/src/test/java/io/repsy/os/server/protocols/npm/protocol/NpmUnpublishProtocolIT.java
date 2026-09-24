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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1289: {@code npm unpublish} over the wire. The npm client (see {@code libnpmpublish}'s {@code
 * unpublish}) does not call the facade; it sends these requests, and until this ticket the first of
 * them ({@code PUT /pkg/-rev/<rev>}) parsed {@code pkg/-rev/<rev>} as the package name and answered
 * 404 {@code itemNotFound}, which the client treats as "already unpublished" and reports as a
 * success:
 *
 * <ol>
 *   <li>{@code GET /pkg?write=true}, the packument;
 *   <li>{@code PUT /pkg/-rev/<rev>} with the packument minus the version (and its dist-tags);
 *   <li>{@code GET /pkg?write=true} again, for a fresh {@code _rev};
 *   <li>{@code DELETE /pkg/-/pkg-<version>.tgz/-rev/<rev>}, the path of the version's {@code
 *       dist.tarball}.
 * </ol>
 *
 * <p>The only version of a package is unpublished with {@code DELETE /pkg/-rev/<rev>} alone. A
 * scoped name is {@code @scope%2fpkg} on the wire in the first three (the connector decodes it), so
 * these tests send it decoded, the way the router sees it; the tarball path has a plain slash.
 * Repsy does not serve a {@code _rev}, so the client sends the literal {@code undefined}.
 *
 * <p>{@link UsageUpdateService} is mocked, and every test runs in the default rolled-back
 * transaction, like {@link NpmPublishProtocolIT}.
 */
@DisplayName("npm unpublish over the wire (RPS-1289)")
class NpmUnpublishProtocolIT extends AbstractIntegrationTest {

  private static final String HOST = "http://localhost:9090";
  private static final String REV = "undefined";
  private static final String PACKAGE_PATH = "/{repo}/{packagePath}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;

  private Repo npmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("npm-unpub"));
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static String bareName(final String packageName) {
    return packageName.substring(packageName.indexOf('/') + 1);
  }

  private static String tarballPath(
      final Repo repo, final String packageName, final String version) {
    return "/"
        + repo.getName()
        + "/"
        + packageName
        + "/-/"
        + bareName(packageName)
        + "-"
        + version
        + ".tgz";
  }

  private byte[] publishBody(final Repo repo, final String packageName, final String version) {
    final var dist = new LinkedHashMap<String, Object>();
    dist.put("tarball", HOST + tarballPath(repo, packageName, version));

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", packageName);
    versionMetadata.put("version", version);
    versionMetadata.put("dist", dist);

    final var tarball = ("tarball of " + version).getBytes(StandardCharsets.UTF_8);
    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", packageName);
    body.put("name", packageName);
    body.put("dist-tags", Map.of("latest", version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            bareName(packageName) + "-" + version + ".tgz",
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarball),
                "length",
                tarball.length)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  private void publish(final Repo repo, final String packageName, final String version)
      throws Exception {

    final var response =
        this.protocol(
            put(PACKAGE_PATH, repo.getName(), packageName)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.publishBody(repo, packageName, version)));

    assertThat(response.getStatus()).as("publish %s@%s", packageName, version).isEqualTo(200);
  }

  /** {@code GET /pkg?write=true}, the first request of the client. */
  private Map<String, Object> readPackument(final Repo repo, final String packageName)
      throws Exception {

    final var response =
        this.protocol(
            get(PACKAGE_PATH, repo.getName(), packageName)
                .queryParam("write", "true")
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));
    assertThat(response.getStatus()).isEqualTo(200);

    return this.objectMapper.readValue(
        response.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  /** What {@code npm unpublish pkg@version} does to the packument before it sends it back. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> withoutVersion(
      final Map<String, Object> packument, final String version) {

    ((Map<String, Object>) packument.get("versions")).remove(version);

    final var tags = (Map<String, String>) packument.get("dist-tags");
    tags.values().removeIf(version::equals);

    final var remaining = ((Map<String, Object>) packument.get("versions")).keySet();

    if (!tags.containsKey("latest") && !remaining.isEmpty()) {
      tags.put("latest", remaining.stream().sorted().reduce((_, last) -> last).orElseThrow());
    }

    return packument;
  }

  private MockHttpServletResponse putPackument(
      final Repo repo, final String packageName, final Map<String, Object> packument)
      throws Exception {

    return this.protocol(
        put(PACKAGE_PATH + "/-rev/{rev}", repo.getName(), packageName, REV)
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.objectMapper.writeValueAsBytes(packument)));
  }

  private MockHttpServletResponse deleteTarball(
      final Repo repo, final String packageName, final String version) throws Exception {

    return this.protocol(
        delete(tarballPath(repo, packageName, version) + "/-rev/{rev}", REV)
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private MockHttpServletResponse deleteWholePackage(final Repo repo, final String packageName)
      throws Exception {

    return this.protocol(
        delete(PACKAGE_PATH + "/-rev/{rev}", repo.getName(), packageName, REV)
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private MockHttpServletResponse getTarball(
      final Repo repo, final String packageName, final String version) throws Exception {

    return this.protocol(
        get(tarballPath(repo, packageName, version))
            .header(AUTHORIZATION, this.adminProtocolBearerToken()));
  }

  private List<String> storedVersions(final Repo repo, final String packageName) {
    this.entityManager.flush();
    this.entityManager.clear();

    final var scoped = packageName.startsWith("@");
    final var scope = scoped ? packageName.substring(1, packageName.indexOf('/')) : null;

    return this.npmPackageRepository
        .findByRepoIdAndScopeAndName(repo.getId(), scope, bareName(packageName))
        .map(
            npmPackage ->
                this.packageVersionRepository.findByNpmPackageId(npmPackage.getId()).stream()
                    .map(PackageVersion::getVersion)
                    .sorted()
                    .toList())
        .orElse(List.of());
  }

  private boolean packageExists(final Repo repo, final String packageName) {
    this.entityManager.flush();
    this.entityManager.clear();

    final var scoped = packageName.startsWith("@");
    final var scope = scoped ? packageName.substring(1, packageName.indexOf('/')) : null;

    return this.npmPackageRepository
        .findByRepoIdAndScopeAndName(repo.getId(), scope, bareName(packageName))
        .isPresent();
  }

  @SuppressWarnings("unchecked")
  private void assertPackumentHasOnly(
      final Repo repo, final String packageName, final String latest, final String... versions)
      throws Exception {

    final var packument = this.readPackument(repo, packageName);

    assertThat(((Map<String, Object>) packument.get("versions")).keySet())
        .containsExactlyInAnyOrder(versions);
    assertThat(((Map<String, String>) packument.get("dist-tags")).get("latest")).isEqualTo(latest);
  }

  /** The whole {@code npm unpublish pkg@version} sequence, with the asserts a client makes. */
  private void unpublishOneVersion(final Repo repo, final String packageName, final String version)
      throws Exception {

    final var packument = this.readPackument(repo, packageName);
    final var put = this.putPackument(repo, packageName, withoutVersion(packument, version));
    assertThat(put.getStatus()).as("PUT packument: %s", put.getContentAsString()).isEqualTo(200);

    this.readPackument(repo, packageName);

    final var deletedTarball = this.deleteTarball(repo, packageName, version);
    assertThat(deletedTarball.getStatus())
        .as("DELETE tarball: %s", deletedTarball.getContentAsString())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("unpublishes the latest of two versions of an unscoped package")
  void unpublishesAVersionOfAnUnscopedPackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");

    this.unpublishOneVersion(repo, name, "1.1.0");

    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0");
    this.assertPackumentHasOnly(repo, name, "1.0.0", "1.0.0");
    assertThat(this.getTarball(repo, name, "1.1.0").getStatus()).isEqualTo(404);
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("unpublishes a version of a scoped package")
  void unpublishesAVersionOfAScopedPackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "@unpub" + randomTag() + "/scoped";
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");
    this.publish(repo, name, "2.0.0");

    this.unpublishOneVersion(repo, name, "1.1.0");

    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "2.0.0");
    this.assertPackumentHasOnly(repo, name, "2.0.0", "1.0.0", "2.0.0");
    assertThat(this.getTarball(repo, name, "1.1.0").getStatus()).isEqualTo(404);
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
    assertThat(this.getTarball(repo, name, "2.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the tarball request never deletes the package it belongs to")
  void theTarballRequestKeepsThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");

    // A version that is already gone: the tarball request is acknowledged and changes nothing.
    final var response = this.deleteTarball(repo, name, "0.9.0");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the tarball request of a version that is still published is a 409")
  void theTarballRequestOfAPublishedVersionIsAConflict() throws Exception {
    final var repo = this.npmRepo();
    final var name = "@unpub" + randomTag() + "/kept";
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");

    final var response = this.deleteTarball(repo, name, "1.0.0");

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
        .isEqualTo("npmVersionStillPublished");
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("unpublishing the only version of an unscoped package deletes the package")
  void unpublishesTheOnlyVersionOfAnUnscopedPackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");

    // libnpmpublish sends just this when the packument has one version.
    final var response = this.deleteWholePackage(repo, name);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.packageExists(repo, name)).isFalse();
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(404);
    assertThat(
            this.protocol(
                    get(PACKAGE_PATH, repo.getName(), name)
                        .header(AUTHORIZATION, this.adminProtocolBearerToken()))
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("unpublishing the only version of a scoped package deletes the package")
  void unpublishesTheOnlyVersionOfAScopedPackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "@unpub" + randomTag() + "/only";
    this.publish(repo, name, "1.0.0");

    final var response = this.deleteWholePackage(repo, name);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.packageExists(repo, name)).isFalse();
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a packument PUT that removes the last version deletes the package")
  void aPackumentWithoutVersionsDeletesThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");

    final var response =
        this.putPackument(repo, name, withoutVersion(this.readPackument(repo, name), "1.0.0"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.packageExists(repo, name)).isFalse();
    // The tarball request that follows finds no package: still a success for the client.
    assertThat(this.deleteTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a stale payload is a 409 and unpublishes nothing")
  void aStalePayloadIsAConflict() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");

    // The client reads the package, and 2.0.0 is published before its PUT arrives.
    final var payload = withoutVersion(this.readPackument(repo, name), "1.0.0");
    this.publish(repo, name, "2.0.0");

    final var response = this.putPackument(repo, name, payload);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
        .isEqualTo("unpublishPayloadStale");
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "2.0.0");
    this.assertPackumentHasOnly(repo, name, "2.0.0", "1.0.0", "1.1.0", "2.0.0");
    assertThat(this.getTarball(repo, name, "1.0.0").getStatus()).isEqualTo(200);
    assertThat(this.getTarball(repo, name, "2.0.0").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a payload that removes no version is a 409")
  void aPayloadThatRemovesNothingIsAConflict() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");
    this.publish(repo, name, "1.1.0");

    final var response = this.putPackument(repo, name, this.readPackument(repo, name));

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
        .isEqualTo("unpublishPayloadStale");
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("deprecating still goes through PUT /pkg, scoped and unscoped")
  void deprecateKeepsItsRoute() throws Exception {
    final var repo = this.npmRepo();

    for (final var name : List.of("unpub-" + randomTag(), "@unpub" + randomTag() + "/deprecated")) {
      this.publish(repo, name, "1.0.0");
      this.publish(repo, name, "1.1.0");

      final var packument = this.readPackument(repo, name);
      final var versions = (Map<String, Object>) packument.get("versions");
      ((Map<String, Object>) versions.get("1.0.0")).put("deprecated", "use 1.1.0");

      final var response =
          this.protocol(
              put(PACKAGE_PATH, repo.getName(), name)
                  .header(AUTHORIZATION, this.adminProtocolBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(this.objectMapper.writeValueAsBytes(packument)));

      assertThat(response.getStatus()).isEqualTo(200);
      final var stored = (Map<String, Object>) this.readPackument(repo, name).get("versions");
      assertThat(((Map<String, Object>) stored.get("1.0.0")).get("deprecated"))
          .isEqualTo("use 1.1.0");
      assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    }
  }

  @Test
  @DisplayName("a tarball path is not a packument PUT and a PUT of it is unknown")
  void aTarballPathIsNotAPutRoute() throws Exception {
    final var repo = this.npmRepo();
    final var name = "unpub-" + randomTag();
    this.publish(repo, name, "1.0.0");

    final var response =
        this.protocol(
            put(tarballPath(repo, name, "1.0.0") + "/-rev/{rev}", REV)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0");
  }
}
