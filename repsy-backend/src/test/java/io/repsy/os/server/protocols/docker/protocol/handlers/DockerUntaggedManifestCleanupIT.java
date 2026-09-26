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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.bytes;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.index;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("Deleting the untagged manifests of a Docker repository (RPS-1216)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DockerUntaggedManifestCleanupIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @Autowired private ImageRepository imageRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String panelToken;

  @BeforeEach
  void setUpWire() {
    final var admin = this.commitUser(UserRole.ADMIN);
    this.panelToken = this.panelTokenOf(admin);
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.protocolBearerTokenFor(this.userRepository.findById(admin).orElseThrow()));
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete: images, tags, manifests, edges,
    // layers.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private UUID commitUser(final UserRole role) {
    final var username = uniqueUsername("untagged");
    final var info = this.userTxService.create(username, role, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());

    return info.getId();
  }

  private String panelTokenOf(final UUID userId) {
    return this.bearerTokenFor(
        userId, this.userRepository.findById(userId).orElseThrow().getUsername());
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-untagged");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  /** Pushes an image manifest built from {@code layer} and answers its JSON. */
  private String push(
      final Repo repo, final String image, final String reference, final String layer)
      throws Exception {
    this.wire.pushBlobsOf(repo, image, layer);
    final var manifest = imageManifest(layer);
    final var response = this.wire.putImage(repo, image, reference, manifest);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return manifest;
  }

  /** Pushes a manifest by its digest alone, so no tag points at it. */
  private String pushByDigest(final Repo repo, final String image, final String layer)
      throws Exception {
    return this.push(repo, image, sha256(bytes(imageManifest(layer))), layer);
  }

  private String digestOf(final String manifest) {
    return sha256(bytes(manifest));
  }

  private int status(final Repo repo, final String image, final String manifest) throws Exception {
    return this.wire.getManifest(repo, image, this.digestOf(manifest)).getStatus();
  }

  private void deleteTag(final Repo repo, final String image, final String tag) throws Exception {
    this.expectSuccess(
        this.perform(
            delete("/api/docker/images/%s/%s/tags/%s".formatted(repo.getName(), image, tag))
                .header(AUTHORIZATION, this.panelToken)),
        "tagDeleted",
        "Tag deleted.");
  }

  private String cleanup(final Repo repo, final String query) throws Exception {
    return this.expectSuccess(
        this.perform(
            delete("/api/docker/images/manifests/%s/untagged%s".formatted(repo.getName(), query))
                .header(AUTHORIZATION, this.panelToken)),
        "untaggedManifestsDeleted",
        "Untagged manifests deleted.");
  }

  private String cleanup(final Repo repo) throws Exception {
    return this.cleanup(repo, "");
  }

  /** The image as the panel's list shows it. */
  private String summary(final Repo repo) throws Exception {
    return this.expectSuccess(
        this.perform(
            get("/api/docker/images/%s/%s/summary".formatted(repo.getName(), IMAGE))
                .header(AUTHORIZATION, this.panelToken)),
        "imageFetched",
        "Image is fetched.");
  }

  private long layerBytes(final Repo repo) {
    final var size =
        this.jdbcTemplate.queryForObject(
            "select sum(size) from docker_layer where repo_id = ?", Long.class, repo.getId());

    return size == null ? 0 : size;
  }

  private static Number number(final String body, final String field) {
    return JsonPath.read(body, field.startsWith("data.") ? "$." + field : "$.data." + field);
  }

  private void assertResult(
      final String body,
      final int deleted,
      final long freedManifestBytes,
      final int layers,
      final long layerBytes) {
    assertThat(number(body, "deletedManifests").intValue())
        .as("deletedManifests")
        .isEqualTo(deleted);
    assertThat(number(body, "freedManifestBytes").longValue())
        .as("freedManifestBytes")
        .isEqualTo(freedManifestBytes);
    assertThat(number(body, "orphanLayersScheduled").intValue())
        .as("orphanLayersScheduled")
        .isEqualTo(layers);
    assertThat(number(body, "orphanLayerBytes").longValue())
        .as("orphanLayerBytes")
        .isEqualTo(layerBytes);
  }

  private List<String> imageNames(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        "select name from docker_image where repo_id = ?", String.class, repo.getId());
  }

  private int manifestRows(final Repo repo) {
    return this.count(
        "select count(*) from docker_manifest m join docker_image i on i.id = m.image_id"
            + " where i.repo_id = ?",
        repo.getId());
  }

  private int layerRows(final Repo repo) {
    return this.count("select count(*) from docker_layer where repo_id = ?", repo.getId());
  }

  private int edgeRows(final Repo repo) {
    return this.count(
        "select count(*) from docker_manifest_child c"
            + " join docker_manifest m on m.id = c.parent_id"
            + " join docker_image i on i.id = m.image_id where i.repo_id = ?",
        repo.getId());
  }

  private int count(final String sql, final Object... arguments) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);

    return count == null ? 0 : count;
  }

  private long manifestFiles(final Repo repo) {
    final var directory = storageDirOf(repo).resolve("manifests");

    try (final var files = java.nio.file.Files.list(directory)) {
      return files.count();
    } catch (final java.io.IOException e) {
      return 0;
    }
  }

  private java.nio.file.Path blobOf(final Repo repo, final String layer) {
    return storageDirOf(repo).resolve("blobs").resolve(sha256(bytes(layer)));
  }

  private java.nio.file.Path manifestFileOf(final Repo repo, final String manifest) {
    return storageDirOf(repo).resolve("manifests").resolve(this.digestOf(manifest));
  }

  private void awaitBlobsDeleted(final Repo repo, final String... layers) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(Stream.of(layers).map(layer -> this.blobOf(repo, layer)))
                    .allSatisfy(blob -> assertThat(blob).doesNotExist()));
  }

  private void verifyRefund(final Repo repo, final long bytes) {
    verify(this.usageUpdateService, timeout(15_000))
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-bytes)));
  }

  private static long length(final String text) {
    return bytes(text).length;
  }

  // -------------------------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "deletes exactly the manifests no tag reaches, refunds their files and frees their layers")
  void deletesTheUntaggedManifestsAndFreesTheirLayers() throws Exception {
    final var repo = this.dockerRepo();
    // Overridden: the first manifest of "latest" stays behind, untagged.
    final var overridden = this.push(repo, IMAGE, "latest", "layer-one");
    final var current = this.push(repo, IMAGE, "latest", "layer-two");
    // A deleted tag leaves its manifest behind, untagged.
    final var deletedTag = this.push(repo, IMAGE, "v1", "layer-three");
    this.deleteTag(repo, IMAGE, "v1");
    // Pushed by digest and never tagged.
    final var bare = this.pushByDigest(repo, IMAGE, "layer-five");
    // A child is only reachable through the tagged index that lists it.
    final var child = this.pushByDigest(repo, IMAGE, "layer-four");
    final var indexJson = index(child);
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, indexJson)
                .getStatus())
        .isEqualTo(201);
    assertThat(this.manifestRows(repo)).isEqualTo(6);
    assertThat(this.manifestFiles(repo)).isEqualTo(6);
    assertThat(this.layerRows(repo)).as("config + five layers").isEqualTo(6);
    final var manifestBytes = length(overridden) + length(deletedTag) + length(bare);
    final var layerBytes = length("layer-one") + length("layer-three") + length("layer-five");
    clearInvocations(this.usageUpdateService);

    final var body = this.cleanup(repo);

    this.assertResult(body, 3, manifestBytes, 3, layerBytes);
    // Rows and files are gone before the response, and the refund was made for exactly them.
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-manifestBytes)));
    assertThat(this.manifestRows(repo)).isEqualTo(3);
    assertThat(this.manifestFiles(repo)).isEqualTo(3);
    assertThat(this.layerRows(repo)).as("the three orphan layer rows are gone").isEqualTo(3);
    assertThat(this.status(repo, IMAGE, overridden)).isEqualTo(404);
    assertThat(this.status(repo, IMAGE, deletedTag)).isEqualTo(404);
    assertThat(this.status(repo, IMAGE, bare)).isEqualTo(404);
    assertThat(this.status(repo, IMAGE, current)).isEqualTo(200);
    assertThat(this.status(repo, IMAGE, child)).as("a child of a tagged index").isEqualTo(200);
    assertThat(this.status(repo, IMAGE, indexJson)).isEqualTo(200);
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(current);
    assertThat(this.edgeRows(repo)).isEqualTo(1);
    // The blobs go in the background and refund themselves.
    this.awaitBlobsDeleted(repo, "layer-one", "layer-three", "layer-five");
    this.verifyRefund(repo, layerBytes);
    assertThat(this.blobOf(repo, "layer-two")).exists();
    assertThat(this.blobOf(repo, "layer-four")).exists();

    // Nothing is left to delete.
    this.assertResult(this.cleanup(repo), 0, 0, 0, 0);
  }

  @Test
  @DisplayName("the image parameter limits the manifests deleted to one image")
  void theImageParameterScopesTheCleanup() throws Exception {
    final var repo = this.dockerRepo();
    final var oldA = this.push(repo, "a", "latest", "layer-a1");
    this.push(repo, "a", "latest", "layer-a2");
    final var oldB = this.push(repo, "b", "latest", "layer-b1");
    this.push(repo, "b", "latest", "layer-b2");

    this.assertResult(this.cleanup(repo, "?image=a"), 1, length(oldA), 1, length("layer-a1"));

    assertThat(this.status(repo, "a", oldA)).isEqualTo(404);
    assertThat(this.status(repo, "b", oldB)).as("the other image is untouched").isEqualTo(200);
    this.expectError(
        this.perform(
            delete("/api/docker/images/manifests/%s/untagged?image=ghost".formatted(repo.getName()))
                .header(AUTHORIZATION, this.panelToken)),
        HttpStatus.NOT_FOUND,
        "imageNotFound",
        "imageNotFound",
        "Image not found.");
    assertThat(this.status(repo, "b", oldB)).isEqualTo(200);

    this.assertResult(this.cleanup(repo), 1, length(oldB), 1, length("layer-b1"));

    assertThat(this.status(repo, "b", oldB)).isEqualTo(404);
    this.awaitBlobsDeleted(repo, "layer-a1", "layer-b1");
  }

  @Test
  @DisplayName(
      "the repo-wide cleanup removes an image whose manifests are all gone, and only that one")
  void theRepoWideCleanupRemovesTheImagesItEmptied() throws Exception {
    final var repo = this.dockerRepo();
    // "emptied": its only tag was deleted, so it stores one untagged manifest and nothing else.
    this.push(repo, "emptied", "latest", "layer-e1");
    this.deleteTag(repo, "emptied", "latest");
    // "mixed": one tag and one untagged manifest, the image stays with the tagged one.
    this.push(repo, "mixed", "latest", "layer-m1");
    final var mixedOld = this.push(repo, "mixed", "latest", "layer-m2");
    // "tagged": nothing to delete.
    this.push(repo, "tagged", "latest", "layer-t1");
    assertThat(this.imageNames(repo)).containsExactlyInAnyOrder("emptied", "mixed", "tagged");

    this.cleanup(repo);

    assertThat(this.imageNames(repo))
        .as("the image with no manifest left is deleted, the others stay")
        .containsExactlyInAnyOrder("mixed", "tagged");
    assertThat(this.status(repo, "mixed", mixedOld)).isEqualTo(200);
    assertThat(this.wire.getManifest(repo, "emptied", "latest").getStatus()).isEqualTo(404);

    // The next push of the deleted name creates it again.
    this.push(repo, "emptied", "latest", "layer-e2");
    assertThat(this.imageNames(repo)).contains("emptied");
  }

  @Test
  @DisplayName("the per-image cleanup removes the image it emptied")
  void thePerImageCleanupRemovesTheImageItEmptied() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "a", "latest", "layer-a1");
    this.deleteTag(repo, "a", "latest");
    this.push(repo, "b", "latest", "layer-b1");
    this.deleteTag(repo, "b", "latest");

    this.cleanup(repo, "?image=a");

    assertThat(this.imageNames(repo)).containsExactly("b");
    this.expectError(
        this.perform(
            delete("/api/docker/images/manifests/%s/untagged?image=a".formatted(repo.getName()))
                .header(AUTHORIZATION, this.panelToken)),
        HttpStatus.NOT_FOUND,
        "imageNotFound",
        "imageNotFound",
        "Image not found.");
  }

  @Test
  @DisplayName("an image with a manifest that a tagged index lists is kept by the cleanup")
  void anImageWhoseManifestsAreReachedByATaggedIndexStays() throws Exception {
    final var repo = this.dockerRepo();
    final var child = this.pushByDigest(repo, IMAGE, "layer-child");
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, index(child))
                .getStatus())
        .isEqualTo(201);

    this.cleanup(repo);

    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.status(repo, IMAGE, child)).isEqualTo(200);
  }

  @Test
  @DisplayName("a manifest file another image still needs stays, and nothing is refunded for it")
  void aSharedFileStaysWhileAnotherImageHasTheManifest() throws Exception {
    final var repo = this.dockerRepo();
    final var shared = this.push(repo, "a", "latest", "layer-shared");
    this.push(repo, "b", "latest", "layer-shared");
    // In image a the shared manifest becomes untagged; image b still tags it.
    this.push(repo, "a", "latest", "layer-a2");
    assertThat(this.manifestFiles(repo)).as("one file for the shared digest + a2").isEqualTo(2);

    this.assertResult(this.cleanup(repo), 1, 0, 0, 0);

    assertThat(this.manifestFileOf(repo, shared)).exists();
    assertThat(this.status(repo, "a", shared)).isEqualTo(404);
    assertThat(this.status(repo, "b", shared)).isEqualTo(200);
    assertThat(this.layerRows(repo)).as("image b still uses the layer").isEqualTo(3);

    this.deleteTag(repo, "b", "latest");

    this.assertResult(this.cleanup(repo), 1, length(shared), 1, length("layer-shared"));
    assertThat(this.manifestFileOf(repo, shared)).doesNotExist();
    this.awaitBlobsDeleted(repo, "layer-shared");
  }

  @Test
  @DisplayName("an index nested in a tagged index keeps its children until the tag moves away")
  void nestedIndexesAreFollowedToTheirChildren() throws Exception {
    final var repo = this.dockerRepo();
    final var leaf = this.pushByDigest(repo, IMAGE, "layer-leaf");
    final var inner = index(leaf);
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, this.digestOf(inner), DockerWire.OCI_INDEX, inner)
                .getStatus())
        .isEqualTo(201);
    final var outer = index(inner);
    assertThat(this.wire.putManifest(repo, IMAGE, "top", DockerWire.OCI_INDEX, outer).getStatus())
        .isEqualTo(201);

    this.assertResult(this.cleanup(repo), 0, 0, 0, 0);

    assertThat(this.manifestRows(repo)).isEqualTo(3);
    assertThat(this.status(repo, IMAGE, leaf)).isEqualTo(200);

    // The tag moves to a plain image: the outer index, the inner one and the leaf are untagged.
    this.push(repo, IMAGE, "top", "layer-plain");
    final var freed = length(outer) + length(inner) + length(leaf);

    this.assertResult(this.cleanup(repo), 3, freed, 1, length("layer-leaf"));

    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.edgeRows(repo)).isZero();
    assertThat(this.status(repo, IMAGE, leaf)).isEqualTo(404);
    this.awaitBlobsDeleted(repo, "layer-leaf");
  }

  @Test
  @DisplayName(
      "the image list counts the untagged manifests and their size through nested indexes, as the"
          + " cleanup removes them (RPS-1350)")
  void theListAgreesWithTheCleanupOnNestedIndexes() throws Exception {
    final var repo = this.dockerRepo();
    final var leaf = this.pushByDigest(repo, IMAGE, "layer-leaf");
    final var inner = index(leaf);
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, this.digestOf(inner), DockerWire.OCI_INDEX, inner)
                .getStatus())
        .isEqualTo(201);
    final var outer = index(inner);
    assertThat(this.wire.putManifest(repo, IMAGE, "top", DockerWire.OCI_INDEX, outer).getStatus())
        .isEqualTo(201);

    // The leaf is two indexes below the tag: the tag reaches it, so it is neither untagged nor
    // left out of the size the tag shows.
    final var tagged = this.summary(repo);

    assertThat(number(tagged, "data.untaggedManifestCount").intValue()).isZero();
    assertThat(number(tagged, "data.untaggedSize").longValue()).isZero();
    assertThat(number(tagged, "data.size").longValue())
        .as("the layers of the leaf are part of the size of the tagged image")
        .isEqualTo(this.layerBytes(repo))
        .isPositive();

    // The tag moves to a plain image: the outer index, the inner one and the leaf are untagged,
    // and the list says exactly what the cleanup then deletes.
    this.push(repo, IMAGE, "top", "layer-plain");

    final var untagged = this.summary(repo);

    assertThat(number(untagged, "data.untaggedManifestCount").intValue()).isEqualTo(3);
    assertThat(number(untagged, "data.untaggedSize").longValue())
        .as("the config blob the tagged manifest shares is not counted")
        .isEqualTo(length("layer-leaf"));

    final var result = this.cleanup(repo);

    assertThat(number(result, "deletedManifests").intValue())
        .isEqualTo(number(untagged, "data.untaggedManifestCount").intValue());
    assertThat(number(result, "orphanLayerBytes").longValue())
        .isEqualTo(number(untagged, "data.untaggedSize").longValue());
  }

  @Test
  @DisplayName("a repo without images answers zeros")
  void anEmptyRepoHasNothingToDelete() throws Exception {
    final var repo = this.dockerRepo();

    this.assertResult(this.cleanup(repo), 0, 0, 0, 0);
  }

  @Test
  @DisplayName("the layers of a manifest a tag points at are never swept")
  void taggedManifestsKeepTheirLayers() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var layers = this.layerRows(repo);

    this.assertResult(this.cleanup(repo), 0, 0, 0, 0);

    assertThat(this.layerRows(repo)).isEqualTo(layers);
    assertThat(this.blobOf(repo, "layer-one")).exists();
    assertThat(this.manifestFileOf(repo, manifest)).exists();
    assertThat(this.manifestFiles(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "needs the manage permission: anonymous is refused with 401, a read-only user with 403")
  void protectsTheCleanup() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    this.push(repo, IMAGE, "latest", "layer-two");
    final var path = "/api/docker/images/manifests/%s/untagged".formatted(repo.getName());
    final var user = this.commitUser(UserRole.USER);

    this.expectError(
        this.perform(delete(path)),
        HttpStatus.UNAUTHORIZED,
        "loginRequired",
        "unAuthorized",
        "Please log in: the credentials are missing or invalid, or the account is gone.");
    this.expectError(
        this.perform(delete(path).header(AUTHORIZATION, this.panelTokenOf(user))),
        HttpStatus.FORBIDDEN,
        "accessDenied",
        "accessDenied",
        "Access Denied. Please check your credentials.");

    assertThat(this.status(repo, IMAGE, manifest)).as("nothing was deleted").isEqualTo(200);
    assertThat(this.manifestRows(repo)).isEqualTo(2);
  }
}
