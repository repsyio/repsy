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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1272: adding or removing an npm dist-tag leaves storage and the database in agreement,
 * whichever half of it fails, and cannot lose an update against a publish of the same package. And
 * a tarball that storage has without a version row is treated as the orphan of a failed publish.
 *
 * <p>A tag change writes the tag row first (under the package row lock, flushed) and the package
 * metadata second, in the same transaction, like a publish does (RPS-1124, see {@link
 * NpmPublishStorageConsistencyIT}). Both a publish and a tag change read the one metadata file,
 * change it and write it back, so without the shared lock the later write drops the earlier one's
 * change.
 *
 * <p>Runs without a test transaction for the same reasons as {@link
 * NpmPublishStorageConsistencyIT}: a failed row write aborts a PostgreSQL transaction, and a race
 * needs both requests to commit. It deletes the repos and users it commits, and the constraint and
 * trigger it adds.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("npm dist-tags keep storage and the database in agreement (RPS-1272)")
class NpmDistTagStorageConsistencyIT extends AbstractIntegrationTest {

  /** A tag the database refuses to insert through {@link #rejectTagInsertsAtTheDatabase()}. */
  private static final String REJECTED_TAG = "reject-me";

  /** A tag the database refuses to delete through {@link #rejectTagDeletesAtTheDatabase()}. */
  private static final String UNDELETABLE_TAG = "keep-me";

  private static final String REJECT_TAG_CONSTRAINT = "ch_npm_package_dist_tag__it_rejected";
  private static final String REJECT_DELETE_TRIGGER = "it_reject_npm_dist_tag_delete";
  private static final String HOST = "http://localhost:9090";
  private static final String PUBLISH_PATH = "/{repo}/{packagePath}";
  private static final String DIST_TAG_PATH = "/{repo}/-/package/{name}/dist-tags/{tag}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private NpmStorageService npmStorageService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // The trigger goes first: deleting the repos deletes the tag rows it would refuse to delete.
    this.jdbcTemplate.execute(
        "drop trigger if exists " + REJECT_DELETE_TRIGGER + " on npm_package_dist_tag");
    this.jdbcTemplate.execute("drop function if exists " + REJECT_DELETE_TRIGGER + "()");
    this.jdbcTemplate.execute(
        "alter table npm_package_dist_tag drop constraint if exists " + REJECT_TAG_CONSTRAINT);
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /** Makes the database refuse a tag row named {@link #REJECTED_TAG}. */
  private void rejectTagInsertsAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table npm_package_dist_tag add constraint "
            + REJECT_TAG_CONSTRAINT
            + " check (tag_name <> '"
            + REJECTED_TAG
            + "')");
  }

  /** Makes the database refuse to delete a tag row named {@link #UNDELETABLE_TAG}. */
  private void rejectTagDeletesAtTheDatabase() {
    this.jdbcTemplate.execute(
        "create function "
            + REJECT_DELETE_TRIGGER
            + "() returns trigger language plpgsql as $$ begin if old.tag_name = '"
            + UNDELETABLE_TAG
            + "' then raise exception 'the test refuses this delete'; end if; return old; end $$");
    this.jdbcTemplate.execute(
        "create trigger "
            + REJECT_DELETE_TRIGGER
            + " before delete on npm_package_dist_tag for each row execute function "
            + REJECT_DELETE_TRIGGER
            + "()");
  }

  private Repo npmRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("npm-tag");
    final var created = this.repoTxService.createRepo(name, RepoType.NPM, false, null);
    this.createdRepoIds.add(created.getId());
    this.npmStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("npm-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniquePackageName() {
    return "disttag-" + randomTag();
  }

  private byte[] publishBody(
      final Repo repo, final String name, final String version, final byte[] tarball) {

    final var dist = new LinkedHashMap<String, Object>();
    dist.put(
        "tarball",
        HOST + "/" + repo.getName() + "/" + name + "/-/" + name + "-" + version + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", name);
    versionMetadata.put("version", version);
    versionMetadata.put("description", "version " + version);
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

  private MockHttpServletResponse publish(
      final Repo repo,
      final String name,
      final String version,
      final byte[] tarball,
      final String token)
      throws Exception {

    final var request =
        put(PUBLISH_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.publishBody(repo, name, version, tarball));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /** {@code npm dist-tag add}: the body is the version as a JSON string. */
  private MockHttpServletResponse addTag(
      final Repo repo,
      final String name,
      final String tag,
      final String version,
      final String token)
      throws Exception {

    final var request =
        put(DIST_TAG_PATH, repo.getName(), name, tag)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("\"" + version + "\"");

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private MockHttpServletResponse removeTag(
      final Repo repo, final String name, final String tag, final String token) throws Exception {

    final var request =
        delete(DIST_TAG_PATH, repo.getName(), name, tag).header(AUTHORIZATION, token);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static byte[] tarballOf(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static Path tarballFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve(name).resolve(name + "-" + version + ".tgz");
  }

  private static Path metadataFile(final Repo repo, final String name) {
    return storageDirOf(repo).resolve(name).resolve("package.json");
  }

  private Map<String, Object> metadata(final Repo repo, final String name) throws IOException {
    return this.objectMapper.readValue(
        Files.readAllBytes(metadataFile(repo, name)), new TypeReference<>() {});
  }

  /** The version names the stored package metadata lists. */
  @SuppressWarnings("unchecked")
  private List<String> metadataVersions(final Repo repo, final String name) throws IOException {
    return List.copyOf(((Map<String, Object>) this.metadata(repo, name).get("versions")).keySet());
  }

  /** The dist-tags of the stored package metadata. */
  @SuppressWarnings("unchecked")
  private Map<String, String> metadataTags(final Repo repo, final String name) throws IOException {
    return new TreeMap<>((Map<String, String>) this.metadata(repo, name).get("dist-tags"));
  }

  /** The dist-tags the database has for the package, as tag to version. */
  private Map<String, String> storedTags(final Repo repo, final String name) {
    final var tags = new TreeMap<String, String>();

    this.jdbcTemplate.query(
        """
        select t.tag_name, v.version from npm_package_dist_tag t
          join npm_package_version v on v.id = t.package_version_id
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ?
        """,
        rs -> {
          tags.put(rs.getString(1), rs.getString(2));
        },
        repo.getId(),
        name);

    return tags;
  }

  private int storedVersionCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from npm_package_version v
              join npm_package p on p.id = v.package_id
            where p.repo_id = ? and p.name = ?
            """,
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private String storedLatest(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForObject(
        "select latest from npm_package where repo_id = ? and name = ?",
        String.class,
        repo.getId(),
        name);
  }

  /** A package with two published versions, the second one being its latest. */
  private String twoVersions(final Repo repo, final String token) throws Exception {
    final var name = uniquePackageName();
    assertThat(this.publish(repo, name, "1.0.0", tarballOf("one"), token).getStatus())
        .isEqualTo(200);
    assertThat(this.publish(repo, name, "1.1.0", tarballOf("two"), token).getStatus())
        .isEqualTo(200);

    return name;
  }

  @Test
  @DisplayName("adding a tag records the row and the package metadata together")
  void addingATagStoresRowAndMetadata() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);

    final var response = this.addTag(repo, name, "beta", "1.0.0", token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.storedTags(repo, name)).containsEntry("beta", "1.0.0");
    assertThat(this.metadataTags(repo, name)).containsEntry("beta", "1.0.0");
  }

  @Test
  @DisplayName("moving latest keeps the row, the package's latest and the metadata in agreement")
  void movingLatestKeepsEverythingInAgreement() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);

    final var response = this.addTag(repo, name, "latest", "1.0.0", token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.storedTags(repo, name)).containsEntry("latest", "1.0.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.0.0");
    assertThat(this.metadataTags(repo, name)).containsEntry("latest", "1.0.0");
  }

  @Test
  @DisplayName("a version that does not exist is refused with 400 and nothing is written")
  void aMissingVersionIsARejectedTag() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));

    final var response = this.addTag(repo, name, "beta", "9.9.9", token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedTags(repo, name)).doesNotContainKey("beta");
  }

  @Test
  @DisplayName("removing a tag deletes the row and the entry in the package metadata")
  void removingATagRemovesRowAndMetadata() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    assertThat(this.addTag(repo, name, "beta", "1.0.0", token).getStatus()).isEqualTo(200);

    final var response = this.removeTag(repo, name, "beta", token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.storedTags(repo, name)).doesNotContainKey("beta");
    assertThat(this.metadataTags(repo, name)).doesNotContainKey("beta");
  }

  @Test
  @DisplayName("a tag row the database rejects never reaches the package metadata")
  void rejectedTagRowLeavesTheMetadataAsItWas() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.rejectTagInsertsAtTheDatabase();

    final var response = this.addTag(repo, name, REJECTED_TAG, "1.0.0", token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedTags(repo, name)).doesNotContainKey(REJECTED_TAG);
  }

  @Test
  @DisplayName("a tag delete the database rejects leaves the tag in the package metadata")
  void rejectedTagDeleteLeavesTheMetadataAsItWas() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    assertThat(this.addTag(repo, name, UNDELETABLE_TAG, "1.0.0", token).getStatus()).isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.rejectTagDeletesAtTheDatabase();

    final var response = this.removeTag(repo, name, UNDELETABLE_TAG, token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedTags(repo, name)).containsEntry(UNDELETABLE_TAG, "1.0.0");
    assertThat(this.metadataTags(repo, name)).containsEntry(UNDELETABLE_TAG, "1.0.0");
  }

  @Test
  @DisplayName("a failed metadata write rolls the new tag row back and puts the metadata back")
  void failedMetadataWriteRollsBackAnAddedTag() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    // Writes the metadata like the real service, then fails, as a storage that dies mid-write.
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());

    final var response = this.addTag(repo, name, "beta", "1.0.0", token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedTags(repo, name)).doesNotContainKey("beta");
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
  }

  @Test
  @DisplayName("a failed metadata write puts a moved latest back, row, latest and metadata")
  void failedMetadataWriteRollsBackAMovedLatest() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());

    final var response = this.addTag(repo, name, "latest", "1.0.0", token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedTags(repo, name)).containsEntry("latest", "1.1.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.1.0");
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
  }

  @Test
  @DisplayName("a failed metadata write keeps the tag row a remove was deleting")
  void failedMetadataWriteRollsBackARemovedTag() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);
    assertThat(this.addTag(repo, name, "beta", "1.0.0", token).getStatus()).isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .removeDistributionTag(any(), any(), any(), any());

    final var response = this.removeTag(repo, name, "beta", token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedTags(repo, name)).containsEntry("beta", "1.0.0");
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.metadataTags(repo, name)).containsEntry("beta", "1.0.0");
  }

  /**
   * Holds the first call of {@code proceed} until the returned latch is released, and lets the
   * calls after it run as usual. The call is inside the transaction of the request that makes it,
   * which holds the package row locked, so a request held here blocks every other one for the
   * package.
   */
  private CountDownLatch hold(final CountDownLatch held) throws Exception {
    final var release = new CountDownLatch(1);

    doAnswer(
            invocation -> {
              held.countDown();
              release.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());

    return release;
  }

  @Test
  @DisplayName("a tag added while a publish is writing waits for it, and neither is lost")
  void tagAddedDuringAPublishSurvives() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);

    final var publishWriting = new CountDownLatch(1);
    final var releasePublish = this.hold(publishWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var publish =
          executor.submit(() -> this.publish(repo, name, "1.2.0", tarballOf("three"), token));
      assertThat(publishWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var tag = executor.submit(() -> this.addTag(repo, name, "beta", "1.0.0", token));
      // Long enough for the tag change to reach the package row and wait on the publish's lock.
      // If it is slower the assertions still hold: it simply runs after the publish.
      TimeUnit.MILLISECONDS.sleep(500);
      releasePublish.countDown();

      assertThat(publish.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(tag.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releasePublish.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
    assertThat(this.storedTags(repo, name)).containsEntry("beta", "1.0.0");
    assertThat(this.metadataVersions(repo, name))
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.metadataTags(repo, name))
        .as("the tag did not overwrite the publish's metadata, nor the publish the tag")
        .containsEntry("beta", "1.0.0")
        .containsEntry("latest", "1.2.0");
  }

  @Test
  @DisplayName("a publish that starts while a tag is being added waits for it, and neither is lost")
  void publishDuringATagChangeSurvives() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = this.twoVersions(repo, token);

    final var tagWriting = new CountDownLatch(1);
    final var releaseTag = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              tagWriting.countDown();
              releaseTag.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var tag = executor.submit(() -> this.addTag(repo, name, "beta", "1.0.0", token));
      assertThat(tagWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var publish =
          executor.submit(() -> this.publish(repo, name, "1.2.0", tarballOf("three"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseTag.countDown();

      assertThat(tag.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(publish.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releaseTag.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
    assertThat(this.storedTags(repo, name)).containsEntry("beta", "1.0.0");
    assertThat(this.metadataVersions(repo, name))
        .as("the publish read the metadata the tag change wrote, not the one before it")
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.metadataTags(repo, name)).containsEntry("beta", "1.0.0");
  }

  @Test
  @DisplayName("a tarball without a version row is replaced by the first publish of the package")
  void orphanedTarballOfANewPackageIsReplaced(final CapturedOutput output) throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    this.plantOrphan(repo, name, "1.0.0", "orphan");
    final var tarball = tarballOf("fresh");

    final var response = this.publish(repo, name, "1.0.0", tarball, token);

    assertThat(response.getStatus())
        .as("allowOverride is off, but the database has no such version to protect")
        .isEqualTo(200);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(tarball);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.0.0");
    assertThat(output).contains("Replacing an orphaned tarball of npm package " + name);
  }

  @Test
  @DisplayName("a tarball without a version row is replaced by a new version of a package")
  void orphanedTarballOfANewVersionIsReplaced(final CapturedOutput output) throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = uniquePackageName();
    assertThat(this.publish(repo, name, "1.0.0", tarballOf("one"), token).getStatus())
        .isEqualTo(200);
    this.plantOrphan(repo, name, "1.1.0", "orphan");
    final var tarball = tarballOf("fresh");

    final var response = this.publish(repo, name, "1.1.0", tarball, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(tarballFile(repo, name, "1.1.0")).hasBinaryContent(tarball);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(tarballOf("one"));
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(output).contains("Replacing an orphaned tarball of npm package " + name);
  }

  @Test
  @DisplayName("a failed publish over an orphaned tarball leaves neither the orphan nor rows")
  void failedPublishOverAnOrphanRemovesIt() throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = uniquePackageName();
    assertThat(this.publish(repo, name, "1.0.0", tarballOf("one"), token).getStatus())
        .isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.plantOrphan(repo, name, "1.1.0", "orphan");
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());

    final var response = this.publish(repo, name, "1.1.0", tarballOf("fresh"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
  }

  @Test
  @DisplayName("a version that has its rows is still not replaced when overrides are off")
  void aRegisteredVersionIsNotTreatedAsAnOrphan(final CapturedOutput output) throws Exception {
    final var repo = this.npmRepo(false);
    final var token = this.adminToken();
    final var name = uniquePackageName();
    final var original = tarballOf("original");
    assertThat(this.publish(repo, name, "1.0.0", original, token).getStatus()).isEqualTo(200);

    final var response = this.publish(repo, name, "1.0.0", tarballOf("replacement"), token);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(original);
    assertThat(output).doesNotContain("Replacing an orphaned tarball");
  }

  /** Puts a tarball in storage that no version row belongs to, as a failed old publish left. */
  private void plantOrphan(
      final Repo repo, final String name, final String version, final String content)
      throws IOException {

    final var file = tarballFile(repo, name, version);

    Files.createDirectories(file.getParent());
    Files.write(file, tarballOf(content));
  }
}
