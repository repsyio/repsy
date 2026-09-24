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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1300: a package whose row exists but whose {@code package.json} (or whole directory) is gone
 * from storage can still have a version deleted, unpublished or deprecated, and a tag changed. The
 * database is the source of truth, so the metadata is rebuilt from its rows (and from what is left
 * of the tarballs) and the operation goes on as usual, rolling back with the rows when it fails.
 *
 * <p>Each test loses the file behind the database's back, on purpose, and then acts through the
 * panel facade or the wire protocol the way the real client does (npm reads the packument first).
 *
 * <p>Runs without a test transaction, like {@link NpmDeleteStorageConsistencyIT}: the operations
 * take the package row lock and commit. It deletes the repos and users it commits, and the trigger
 * it adds.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("npm operations on a package whose metadata file is gone (RPS-1300)")
class NpmMissingMetadataIT extends AbstractIntegrationTest {

  private static final String UNDELETABLE_VERSION = "1.9.9";
  private static final String VERSION_TRIGGER = "it_refuse_missing_metadata_version_delete";
  private static final String HOST = "http://localhost:9090";
  private static final String PACKAGE_PATH = "/{repo}/{packagePath}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @MockitoSpyBean private NpmStorageService npmStorageService;

  @MockitoSpyBean(name = "osStorageStrategyNpm")
  private StorageStrategy npmStorageStrategy;

  @Autowired private NpmProtocolFacade npmProtocolFacade;
  @Autowired private NpmApiFacade npmApiFacade;
  @Autowired private RepoTxService repoTxService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    RequestContextHolder.resetRequestAttributes();
    this.jdbcTemplate.execute(
        "drop trigger if exists " + VERSION_TRIGGER + " on npm_package_version");
    this.jdbcTemplate.execute("drop function if exists " + VERSION_TRIGGER + "()");
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private Repo npmRepo() {
    final var name = uniqueRepoName("npm-lost");
    final var created = this.repoTxService.createRepo(name, RepoType.NPM, false, null);
    this.createdRepoIds.add(created.getId());
    this.npmStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private RepoInfo infoOf(final Repo repo) {
    return this.repoTxService.getRepoByNameAndType(repo.getName(), RepoType.NPM).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("npm-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  /** A real npm tarball: a gzipped tar with {@code package/package.json} inside. */
  private static byte[] tarballOf(final String name, final String version) {
    final var manifest =
        """
        {"name":"%s","version":"%s","main":"index.js",
         "dependencies":{"left-pad":"^1.3.0"},"scripts":{"test":"echo ok"}}
        """
            .formatted(name, version)
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    try (final var gzip = new GzipCompressorOutputStream(out);
        final var tar = new TarArchiveOutputStream(gzip)) {
      final var entry = new TarArchiveEntry("package/package.json");
      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
      tar.finish();
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }

    return out.toByteArray();
  }

  private byte[] publishBody(final Repo repo, final String name, final String version) {
    final var tarball = tarballOf(name, version);
    final var dist = new LinkedHashMap<String, Object>();
    dist.put(
        "tarball",
        HOST + "/" + repo.getName() + "/" + name + "/-/" + name + "-" + version + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", name);
    versionMetadata.put("version", version);
    versionMetadata.put("description", "version " + version);
    versionMetadata.put("license", "MIT");
    versionMetadata.put("homepage", "https://example.test/" + name);
    versionMetadata.put("keywords", List.of("alpha", "beta"));
    versionMetadata.put("author", Map.of("name", "Ada", "email", "ada@example.test"));
    versionMetadata.put(
        "repository", Map.of("type", "git", "url", "https://example.test/" + name + ".git"));
    versionMetadata.put("bugs", Map.of("url", "https://example.test/" + name + "/issues"));
    versionMetadata.put("maintainers", List.of(Map.of("name", "Ada", "email", "ada@example.test")));
    versionMetadata.put("dependencies", Map.of("left-pad", "^1.3.0"));
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

    return this.objectMapper.writeValueAsBytes(body);
  }

  private MockHttpServletResponse protocol(
      final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(final Repo repo, final String name, final String version, final String token)
      throws Exception {
    final var response =
        this.protocol(
            put(PACKAGE_PATH, repo.getName(), name)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.publishBody(repo, name, version)));

    assertThat(response.getStatus()).as("publish %s@%s", name, version).isEqualTo(200);
  }

  private String publishedVersions(final Repo repo, final String token, final String... versions)
      throws Exception {
    final var name = "lost-" + randomTag();

    for (final var version : versions) {
      this.publish(repo, name, version, token);
    }

    return name;
  }

  private static Path packageDir(final Repo repo, final String name) {
    return storageDirOf(repo).resolve(name);
  }

  private static Path metadataFile(final Repo repo, final String name) {
    return packageDir(repo, name).resolve("package.json");
  }

  private static Path tarballFile(final Repo repo, final String name, final String version) {
    return packageDir(repo, name).resolve(name + "-" + version + ".tgz");
  }

  /** Loses the metadata file behind the database's back, leaving the tarballs. */
  private static void loseTheMetadataOf(final Repo repo, final String name) throws IOException {
    Files.delete(metadataFile(repo, name));
    assertThat(metadataFile(repo, name)).doesNotExist();
  }

  /** Loses the whole package directory behind the database's back. */
  private static void loseTheDirectoryOf(final Repo repo, final String name) throws IOException {
    try (final var walk = Files.walk(packageDir(repo, name))) {
      walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
    assertThat(packageDir(repo, name)).doesNotExist();
  }

  private Map<String, Object> storedMetadata(final Repo repo, final String name)
      throws IOException {
    return this.objectMapper.readValue(
        Files.readAllBytes(metadataFile(repo, name)), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> storedVersion(
      final Repo repo, final String name, final String version) throws IOException {
    return (Map<String, Object>)
        ((Map<String, Object>) this.storedMetadata(repo, name).get("versions")).get(version);
  }

  @SuppressWarnings("unchecked")
  private List<String> storedMetadataVersions(final Repo repo, final String name)
      throws IOException {
    return List.copyOf(
        ((Map<String, Object>) this.storedMetadata(repo, name).get("versions")).keySet());
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> storedMetadataTags(final Repo repo, final String name)
      throws IOException {
    return new TreeMap<>((Map<String, String>) this.storedMetadata(repo, name).get("dist-tags"));
  }

  private int count(final String sql, final Object... arguments) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);

    return count == null ? 0 : count;
  }

  private List<String> rowVersions(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForList(
        """
        select v.version from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ? order by v.version
        """,
        String.class,
        repo.getId(),
        name);
  }

  private String rowLatest(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForObject(
        "select latest from npm_package where repo_id = ? and name = ?",
        String.class,
        repo.getId(),
        name);
  }

  private String rowDeprecation(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select coalesce(v.deprecation_message, '') from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  private int packageCount(final Repo repo, final String name) {
    return this.count(
        "select count(*) from npm_package where repo_id = ? and name = ?", repo.getId(), name);
  }

  /** {@code GET /pkg?write=true}, the first request of npm unpublish, deprecate and dist-tag. */
  private MockHttpServletResponse getPackument(
      final Repo repo, final String name, final String token, final String accept)
      throws Exception {
    return this.protocol(
        get(PACKAGE_PATH, repo.getName(), name)
            .queryParam("write", "true")
            .header(AUTHORIZATION, token)
            .header(ACCEPT, accept));
  }

  private Map<String, Object> readPackument(final Repo repo, final String name, final String token)
      throws Exception {
    final var response = this.getPackument(repo, name, token, "application/json");
    assertThat(response.getStatus()).as("GET the packument").isEqualTo(200);

    return this.objectMapper.readValue(
        response.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> withoutVersion(
      final Map<String, Object> packument, final String version) {
    ((Map<String, Object>) packument.get("versions")).remove(version);
    ((Map<String, String>) packument.get("dist-tags")).values().removeIf(version::equals);

    return packument;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> withDeprecation(
      final Map<String, Object> packument, final String version, final String message) {
    ((Map<String, Object>) ((Map<String, Object>) packument.get("versions")).get(version))
        .put("deprecated", message);

    return packument;
  }

  private MockHttpServletResponse putPackument(
      final Repo repo, final String name, final Map<String, Object> packument, final String token)
      throws Exception {
    return this.protocol(
        put(PACKAGE_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.objectMapper.writeValueAsBytes(packument)));
  }

  private ProtocolContext contextOf(final Repo repo) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(this.infoOf(repo))
            .build());

    return context;
  }

  /**
   * The panel's delete of a version. The panel request is not the last MockMvc request the test
   * thread still has bound, so that is cleared first: a rebuild reads the address of the request it
   * runs in.
   */
  private void panelDelete(final Repo repo, final String name, final String version)
      throws IOException {
    RequestContextHolder.resetRequestAttributes();
    this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, version);
  }

  private void refuseVersionDeletes() {
    this.jdbcTemplate.execute(
        "create function "
            + VERSION_TRIGGER
            + "() returns trigger language plpgsql as $$ begin if old.version = '"
            + UNDELETABLE_VERSION
            + "' then raise exception 'the test refuses this change'; end if; return old; end $$");
    this.jdbcTemplate.execute(
        "create trigger "
            + VERSION_TRIGGER
            + " before delete on npm_package_version for each row execute function "
            + VERSION_TRIGGER
            + "()");
  }

  /** Makes the removal of any tarball fail, after the file system has been asked to. */
  private void failTarballRemovals() {
    doAnswer(
            invocation -> {
              final var path = invocation.<StoragePath>getArgument(0);

              if (path.getPath().endsWith(".tgz")) {
                throw new IllegalStateException("storage refused to remove " + path.getPath());
              }

              return invocation.callRealMethod();
            })
        .when(this.npmStorageStrategy)
        .delete(any());
  }

  // -------------------------------------------------------------------------------------------
  // Delete from the panel
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deleting a non-last version works when package.json is gone")
  void panelDeleteOfANonLastVersionWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    loseTheMetadataOf(repo, name);

    this.panelDelete(repo, name, "1.1.0");

    assertThat(this.rowVersions(repo, name)).containsExactly("1.0.0", "2.0.0");
    assertThat(this.rowLatest(repo, name)).isEqualTo("2.0.0");
    assertThat(this.storedMetadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "2.0.0");
    assertThat(this.storedMetadataTags(repo, name)).containsExactly(Map.entry("latest", "2.0.0"));
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
    assertThat(tarballFile(repo, name, "2.0.0")).exists();
  }

  @Test
  @DisplayName("deleting the latest version moves latest in the rebuilt metadata too")
  void panelDeleteOfTheLatestVersionWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    loseTheMetadataOf(repo, name);

    this.panelDelete(repo, name, "2.0.0");

    assertThat(this.rowLatest(repo, name)).isEqualTo("1.1.0");
    assertThat(this.storedMetadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "1.1.0");
    assertThat(this.storedMetadataTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(this.storedMetadata(repo, name)).containsEntry("description", "version 1.1.0");
  }

  @Test
  @DisplayName("deleting a non-last version works when the whole package directory is gone")
  void panelDeleteOfANonLastVersionWithoutTheDirectory() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    loseTheDirectoryOf(repo, name);

    this.panelDelete(repo, name, "1.0.0");

    assertThat(this.rowVersions(repo, name)).containsExactly("1.1.0", "2.0.0");
    assertThat(this.storedMetadataVersions(repo, name)).containsExactlyInAnyOrder("1.1.0", "2.0.0");
    assertThat(this.storedMetadataTags(repo, name)).containsExactly(Map.entry("latest", "2.0.0"));
    // The tarballs are gone too, so nothing can vouch for them: no invented digests.
    assertThat(this.storedVersion(repo, name, "2.0.0")).doesNotContainKey("dist");
  }

  @Test
  @DisplayName("deleting the only version still works when package.json is gone")
  void panelDeleteOfTheLastVersionWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0");
    loseTheMetadataOf(repo, name);

    this.panelDelete(repo, name, "1.0.0");

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(packageDir(repo, name)).doesNotExist();
  }

  @Test
  @DisplayName("a rebuild from the panel points the tarballs at the registry port, not the panel's")
  void panelRebuildPointsTarballsAtTheRegistry() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "2.0.0");
    loseTheMetadataOf(repo, name);
    final var panelRequest = new MockHttpServletRequest();
    panelRequest.setScheme("http");
    panelRequest.setServerName("registry.test");
    panelRequest.setServerPort(API_PORT);
    panelRequest.setLocalPort(API_PORT);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(panelRequest));

    this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "2.0.0");

    @SuppressWarnings("unchecked")
    final var dist = (Map<String, Object>) this.storedVersion(repo, name, "1.0.0").get("dist");
    assertThat(dist)
        .containsEntry(
            "tarball",
            "http://registry.test:9090/"
                + repo.getName()
                + "/"
                + name
                + "/-/"
                + name
                + "-1.0.0.tgz");
  }

  // -------------------------------------------------------------------------------------------
  // Unpublish, deprecate and tag over the wire
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("npm unpublish of a non-last version works when package.json is gone")
  void protocolUnpublishWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "2.0.0");
    loseTheMetadataOf(repo, name);

    final var payload = withoutVersion(this.readPackument(repo, name, token), "1.1.0");
    final var unpublished =
        this.npmProtocolFacade.unPublishPackageVersion(this.contextOf(repo), null, name, payload);

    assertThat(unpublished).isEqualTo("1.1.0");
    assertThat(this.rowVersions(repo, name)).containsExactly("1.0.0", "2.0.0");
    assertThat(this.storedMetadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "2.0.0");
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
  }

  @Test
  @DisplayName("npm deprecate works when package.json is gone")
  void protocolDeprecateWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    loseTheMetadataOf(repo, name);

    final var payload = withDeprecation(this.readPackument(repo, name, token), "1.0.0", "use 1.1");
    final var response = this.putPackument(repo, name, payload, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.rowDeprecation(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.storedVersion(repo, name, "1.0.0")).containsEntry("deprecated", "use 1.1");
    assertThat(this.storedVersion(repo, name, "1.1.0")).doesNotContainKey("deprecated");
    assertThat(this.storedMetadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "1.1.0");
  }

  @Test
  @DisplayName("npm dist-tag add works when package.json is gone")
  void protocolTagWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    loseTheMetadataOf(repo, name);

    final var response =
        this.protocol(
            put("/{repo}/-/package/{name}/dist-tags/{tag}", repo.getName(), name, "beta")
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"1.0.0\""));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.storedMetadataTags(repo, name))
        .containsExactly(Map.entry("beta", "1.0.0"), Map.entry("latest", "1.1.0"));
  }

  // -------------------------------------------------------------------------------------------
  // What the rebuilt metadata says
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "reading a package whose metadata is gone serves it from the rows, and writes nothing")
  void readingServesTheRowsWithoutWriting() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "2.0.0");
    loseTheMetadataOf(repo, name);

    final var packument = this.readPackument(repo, name, token);

    assertThat(metadataFile(repo, name)).doesNotExist();
    assertThat(packument).containsEntry("name", name);
    assertThat(this.versionsOf(packument)).containsExactlyInAnyOrder("1.0.0", "2.0.0");
    assertThat(this.tagsOf(packument)).containsEntry("latest", "2.0.0");
  }

  @Test
  @DisplayName("the abbreviated packument is rebuilt too")
  void abbreviatedReadOfAPackageWithoutMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");
    loseTheMetadataOf(repo, name);

    final var response =
        this.getPackument(repo, name, token, "application/vnd.npm.install-v1+json");

    assertThat(response.getStatus()).isEqualTo(200);
    final var packument =
        this.objectMapper.readValue(
            response.getContentAsString(StandardCharsets.UTF_8),
            new TypeReference<Map<String, Object>>() {});
    assertThat(this.versionsOf(packument)).containsExactly("1.0.0");
  }

  @Test
  @DisplayName("a package with neither row nor file is still not found")
  void readingAnUnknownPackageIsNotFound() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();

    final var response = this.getPackument(repo, "never-published-" + randomTag(), token, "*/*");

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("the rebuilt version has what the rows and the tarball hold, and invents nothing")
  void rebuiltVersionCarriesRowsAndTarballFacts() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    final var tarball = Files.readAllBytes(tarballFile(repo, name, "1.0.0"));
    loseTheMetadataOf(repo, name);

    this.panelDelete(repo, name, "1.1.0");

    final var metadata = this.storedMetadata(repo, name);
    final var version = this.storedVersion(repo, name, "1.0.0");
    assertThat(metadata).containsEntry("name", name).containsEntry("_id", name);
    assertThat(version)
        .containsEntry("name", name)
        .containsEntry("version", "1.0.0")
        .containsEntry("_id", name + "@1.0.0")
        .containsEntry("description", "version 1.0.0")
        .containsEntry("license", "MIT")
        .containsEntry("homepage", "https://example.test/" + name)
        .containsEntry("keywords", List.of("alpha", "beta"))
        .containsEntry("author", Map.of("name", "Ada", "email", "ada@example.test"))
        .containsEntry(
            "repository", Map.of("type", "git", "url", "https://example.test/" + name + ".git"))
        .containsEntry("bugs", Map.of("url", "https://example.test/" + name + "/issues"))
        .containsEntry("maintainers", List.of(Map.of("name", "Ada", "email", "ada@example.test")))
        // From the tarball's own package.json, which the rows do not keep.
        .containsEntry("dependencies", Map.of("left-pad", "^1.3.0"))
        .containsEntry("main", "index.js")
        .containsEntry("scripts", Map.of("test", "echo ok"));
    final var dist = (Map<String, Object>) version.get("dist");
    assertThat(dist)
        .containsEntry("shasum", DigestUtils.sha1Hex(tarball))
        .containsEntry(
            "integrity",
            "sha512-" + Base64.getEncoder().encodeToString(DigestUtils.sha512(tarball)));
    final var time = (Map<String, String>) metadata.get("time");
    assertThat(time)
        .containsKeys("created", "modified", "1.0.0", "2.0.0")
        .doesNotContainKey("1.1.0");
  }

  @Test
  @DisplayName("a tarball URL is written when the request that rebuilds knows the registry address")
  void rebuiltVersionHasATarballUrlFromTheRequest() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    loseTheMetadataOf(repo, name);

    final var payload = withDeprecation(this.readPackument(repo, name, token), "1.0.0", "old");
    assertThat(this.putPackument(repo, name, payload, token).getStatus()).isEqualTo(200);

    @SuppressWarnings("unchecked")
    final var dist = (Map<String, Object>) this.storedVersion(repo, name, "1.0.0").get("dist");
    assertThat(dist)
        .containsEntry(
            "tarball",
            "http://localhost/" + repo.getName() + "/" + name + "/-/" + name + "-1.0.0.tgz");
  }

  // -------------------------------------------------------------------------------------------
  // Failures roll back, and leave the file as it was
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a tarball that cannot be removed rolls the rows back and the file is gone again")
  void failedTarballRemovalRestoresTheMissingFile() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    loseTheMetadataOf(repo, name);
    this.failTarballRemovals();

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> this.panelDelete(repo, name, "1.1.0"));

    assertThat(this.rowVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "2.0.0");
    assertThat(tarballFile(repo, name, "1.1.0")).exists();
    // The rows are back, so a metadata file without their version would contradict them.
    assertThat(metadataFile(repo, name)).doesNotExist();
  }

  @Test
  @DisplayName("a delete the database refuses leaves the missing file missing")
  void refusedDeleteWritesNothing() throws Exception {
    final var repo = this.npmRepo();
    final var name =
        this.publishedVersions(repo, this.adminToken(), "1.0.0", UNDELETABLE_VERSION, "2.0.0");
    loseTheMetadataOf(repo, name);
    this.refuseVersionDeletes();

    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class, () -> this.panelDelete(repo, name, UNDELETABLE_VERSION));

    assertThat(this.rowVersions(repo, name)).containsExactly("1.0.0", UNDELETABLE_VERSION, "2.0.0");
    assertThat(metadataFile(repo, name)).doesNotExist();
    assertThat(tarballFile(repo, name, UNDELETABLE_VERSION)).exists();
  }

  @Test
  @DisplayName(
      "a rebuilt file that cannot be written rolls the delete back before the tarball goes")
  void failedRebuildWriteKeepsTheVersion() throws Exception {
    final var repo = this.npmRepo();
    final var name = this.publishedVersions(repo, this.adminToken(), "1.0.0", "1.1.0", "2.0.0");
    loseTheMetadataOf(repo, name);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("storage went away");
            })
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> this.panelDelete(repo, name, "1.1.0"));

    assertThat(this.rowVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "2.0.0");
    assertThat(tarballFile(repo, name, "1.1.0")).exists();
    assertThat(metadataFile(repo, name)).doesNotExist();
  }

  @SuppressWarnings("unchecked")
  private static List<String> versionsOf(final Map<String, Object> packument) {
    return List.copyOf(((Map<String, Object>) packument.get("versions")).keySet());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> tagsOf(final Map<String, Object> packument) {
    return (Map<String, String>) packument.get("dist-tags");
  }
}
