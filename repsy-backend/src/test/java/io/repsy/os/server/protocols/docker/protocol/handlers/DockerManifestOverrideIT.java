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
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1216: a Docker manifest is content-addressed. It is stored once per image and digest and
 * stays pullable by that digest however tags move: overriding a tag or deleting it in the panel
 * only moves or removes the tag's pointer.
 *
 * <p>The requests are the real wire protocol. {@link UsageUpdateService} is mocked: it is
 * {@code @Async}, so it cannot see this test's uncommitted data, and the mock records the disk
 * usage the pushes and deletes ask for.
 */
@DisplayName("Docker manifests are content-addressed (RPS-1216)")
class DockerManifestOverrideIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private ManifestChildRepository manifestChildRepository;
  @Autowired private TagRepository tagRepository;
  @Autowired private RepoRepository repoRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private DockerWire wire;

  @BeforeEach
  void setUpWire() {
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.adminProtocolBearerToken());
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private Repo withOverrideDisabled(final Repo repo) {
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setAllowOverride(false);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
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

  private long netUsage(final Repo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  private long manifestFiles(final Repo repo) throws Exception {
    final var directory = storageDirOf(repo).resolve("manifests");

    if (!Files.exists(directory)) {
      return 0;
    }

    try (final var files = Files.list(directory)) {
      return files.count();
    }
  }

  private String tagDelete(final Repo repo, final String image, final String tag) throws Exception {
    return this.expectSuccess(
        this.perform(
            delete("/api/docker/images/%s/%s/tags/%s".formatted(repo.getName(), image, tag))
                .header(AUTHORIZATION, this.adminBearerToken())),
        "tagDeleted",
        "Tag deleted.");
  }

  @Test
  @DisplayName("overriding a tag keeps the previous manifest pullable by its digest")
  void overrideKeepsThePreviousManifestPullableByDigest() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, IMAGE, "latest", "layer-one");
    final var oldDigest = sha256(bytes(first));
    assertThat(this.wire.getManifest(repo, IMAGE, oldDigest).getStatus()).isEqualTo(200);

    final var second = this.push(repo, IMAGE, "latest", "layer-two");

    final var oldByDigest = this.wire.getManifest(repo, IMAGE, oldDigest);
    assertThat(oldByDigest.getStatus()).isEqualTo(200);
    assertThat(oldByDigest.getContentAsString()).isEqualTo(first);
    assertThat(oldByDigest.getHeader("Docker-Content-Digest")).isEqualTo(oldDigest);
    assertThat(this.wire.headManifest(repo, IMAGE, oldDigest).getStatus()).isEqualTo(200);
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString()).isEqualTo(second);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(second))).getContentAsString())
        .isEqualTo(second);
  }

  @Test
  @DisplayName("an override moves only the tag pointer: one tag row, two manifest rows and files")
  void overrideMovesOnlyTheTagPointer() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, IMAGE, "latest", "layer-one");
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var tagBefore =
        this.tagRepository.findByImageIdAndName(image.getId(), "latest").orElseThrow();
    final var tagId = tagBefore.getId();
    final var firstManifestId = tagBefore.getManifest().getId();

    final var second = this.push(repo, IMAGE, "latest", "layer-two");

    final var tags = this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId());
    assertThat(tags).hasSize(1);
    assertThat(tags.getFirst().getId()).isEqualTo(tagId);
    assertThat(tags.getFirst().getDigest()).isEqualTo(sha256(bytes(second)));
    assertThat(tags.getFirst().getManifest().getId()).isNotEqualTo(firstManifestId);
    final var manifests = this.manifestRepository.findAllByImageId(image.getId());
    assertThat(manifests)
        .extracting(manifest -> manifest.getDigest())
        .containsExactlyInAnyOrder(sha256(bytes(first)), sha256(bytes(second)));
    assertThat(manifests).allSatisfy(manifest -> assertThat(manifest.getStorageName()).isNull());
    assertThat(storageDirOf(repo).resolve("manifests").resolve(sha256(bytes(first)))).exists();
    assertThat(storageDirOf(repo).resolve("manifests").resolve(sha256(bytes(second)))).exists();
  }

  @Test
  @DisplayName("pushing the same bytes again adds no row and charges nothing")
  void identicalRePushChargesNothingAndAddsNoRow() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var chargedForTheFirst = this.netUsage(repo);
    assertThat(chargedForTheFirst).isGreaterThan(bytes(manifest).length);
    clearInvocations(this.usageUpdateService);

    for (final var reference : List.of("latest", sha256(bytes(manifest)))) {
      final var again = this.wire.putImage(repo, IMAGE, reference, manifest);
      assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(201);
    }

    assertThat(this.netUsage(repo)).isZero();
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(1);
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .hasSize(1);
    assertThat(this.manifestFiles(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a second tag shares the manifest row and file, and deleting one tag keeps the other")
  void retagSharesOneRowAndOneFile() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "tag-a", "layer-one");
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    clearInvocations(this.usageUpdateService);

    final var retag = this.wire.putImage(repo, IMAGE, "tag-b", manifest);

    assertThat(retag.getStatus()).isEqualTo(201);
    assertThat(this.netUsage(repo)).isZero();
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(1);
    assertThat(this.manifestFiles(repo)).isEqualTo(1);

    this.tagDelete(repo, IMAGE, "tag-a");

    assertThat(this.wire.getManifest(repo, IMAGE, "tag-a").getStatus()).isEqualTo(404);
    assertThat(this.wire.getManifest(repo, IMAGE, "tag-b").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName(
      "deleting a tag in the panel leaves its manifest pullable by digest, files untouched")
  void panelTagDeleteLeavesTheManifestPullableByDigest() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var digest = sha256(bytes(manifest));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    clearInvocations(this.usageUpdateService);

    this.tagDelete(repo, IMAGE, "latest");

    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus()).isEqualTo(404);
    final var byDigest = this.wire.getManifest(repo, IMAGE, digest);
    assertThat(byDigest.getStatus()).isEqualTo(200);
    assertThat(byDigest.getContentAsString()).isEqualTo(manifest);
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(1);
    assertThat(this.manifestFiles(repo)).isEqualTo(1);
    assertThat(this.netUsage(repo)).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("a manifest pushed only by its digest is pullable by that digest, without a tag")
  void bareDigestPushIsPullableByItsDigest(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    final var manifest = imageManifest("layer-one");
    final var reference =
        "sha512".equals(algorithm) ? sha512(bytes(manifest)) : sha256(bytes(manifest));

    final var push = this.wire.putImage(repo, IMAGE, reference, manifest);

    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);
    final var pull = this.wire.getManifest(repo, IMAGE, reference);
    assertThat(pull.getStatus()).isEqualTo(200);
    assertThat(pull.getContentAsString()).isEqualTo(manifest);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .as("a digest reference is not a tag")
        .isEmpty();
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .as("either algorithm finds the manifest")
        .isEqualTo(200);
    assertThat(this.wire.getManifest(repo, IMAGE, sha512(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("the sha512 digest of a manifest pushed by tag finds it, also after an override")
  void sha512FindsAManifestPushedByTag() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, IMAGE, "latest", "layer-one");
    this.push(repo, IMAGE, "latest", "layer-two");

    final var byOldSha512 = this.wire.getManifest(repo, IMAGE, sha512(bytes(first)));

    assertThat(byOldSha512.getStatus()).isEqualTo(200);
    assertThat(byOldSha512.getContentAsString()).isEqualTo(first);
  }

  @Test
  @DisplayName(
      "an index needs its manifests in the image, and nothing is stored when one is missing")
  void indexPushRequiresItsChildrenInTheImage() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    final var child = imageManifest("layer-one");
    final var missing = index(child);

    final var refused = this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, missing);

    assertThat(refused.getStatus()).isEqualTo(404);
    assertThat(refused.getContentAsString()).contains("\"detail\":\"manifestNotFound\"");
    assertThat(this.manifestFiles(repo)).isZero();

    this.wire.putImage(repo, IMAGE, sha256(bytes(child)), child);
    final var accepted = this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, missing);

    assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var indexRow =
        this.manifestRepository
            .findByImageIdAndDigest(image.getId(), sha256(bytes(missing)))
            .orElseThrow();
    final var edges = this.manifestChildRepository.findAllByParentId(indexRow.getId());
    assertThat(edges).hasSize(1);
    assertThat(edges.getFirst().getChild().getDigest()).isEqualTo(sha256(bytes(child)));
    assertThat(edges.getFirst().getPlatform()).isEqualTo("linux/amd64");
    assertThat(this.wire.getManifest(repo, IMAGE, "multi").getContentAsString()).isEqualTo(missing);
  }

  @Test
  @DisplayName("pushing a changed index under the same tag keeps the earlier index and children")
  void indexOverrideKeepsTheEarlierIndex() throws Exception {
    final var repo = this.dockerRepo();
    final var one = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-one"))), "layer-one");
    final var two = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-two"))), "layer-two");
    final var first = index(one, two);
    final var second = index(one);
    assertThat(this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, first).getStatus())
        .isEqualTo(201);

    final var override = this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, second);

    assertThat(override.getStatus()).as(override.getContentAsString()).isEqualTo(201);
    assertThat(this.wire.getManifest(repo, IMAGE, "multi").getContentAsString()).isEqualTo(second);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(first))).getContentAsString())
        .as("the earlier index is still pullable by its digest")
        .isEqualTo(first);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(two))).getStatus())
        .as("a child only the earlier index listed stays stored")
        .isEqualTo(200);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var firstRow =
        this.manifestRepository
            .findByImageIdAndDigest(image.getId(), sha256(bytes(first)))
            .orElseThrow();
    assertThat(this.manifestChildRepository.findAllByParentId(firstRow.getId())).hasSize(2);
  }

  @Test
  @DisplayName(
      "with overriding off a tag cannot move, both manifests stay, and the same bytes pass")
  void overrideRefusedWithoutAllowOverrideLeavesBothManifests() throws Exception {
    final var created = this.dockerRepo();
    final var first = this.push(created, IMAGE, "latest", "layer-one");
    final var repo = this.withOverrideDisabled(created);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-two");

    final var refused = this.wire.putImage(repo, IMAGE, "latest", imageManifest("layer-two"));

    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("\"detail\":\"packageOverrideDisabled\"");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString()).isEqualTo(first);
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(1);
    assertThat(this.manifestFiles(repo)).isEqualTo(1);

    final var same = this.wire.putImage(repo, IMAGE, "latest", first);
    assertThat(same.getStatus())
        .as("pushing what the tag already points at moves nothing")
        .isEqualTo(201);
    final var newTag = this.wire.putImage(repo, IMAGE, "other", first);
    assertThat(newTag.getStatus()).as("a new tag is not an override").isEqualTo(201);
  }

  @Test
  @DisplayName(
      "the manifest file is stored once per repo and digest, shared by the images that have it")
  void usageChargedOncePerDigestPerRepo() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "first", "latest", "layer-one");
    clearInvocations(this.usageUpdateService);

    this.wire.pushBlobsOf(repo, "second", "layer-one");
    clearInvocations(this.usageUpdateService);
    final var shared = this.wire.putImage(repo, "second", "latest", manifest);

    assertThat(shared.getStatus()).isEqualTo(201);
    assertThat(this.netUsage(repo)).isZero();
    assertThat(this.manifestFiles(repo)).isEqualTo(1);

    this.expectSuccess(
        this.perform(
            delete("/api/docker/images/%s/first".formatted(repo.getName()))
                .header(AUTHORIZATION, this.adminBearerToken())),
        "imageDeleted",
        "Image deleted.");
    assertThat(this.manifestFiles(repo)).as("the other image still needs the file").isEqualTo(1);
    assertThat(this.wire.getManifest(repo, "second", "latest").getContentAsString())
        .isEqualTo(manifest);

    clearInvocations(this.usageUpdateService);
    this.expectSuccess(
        this.perform(
            delete("/api/docker/images/%s/second".formatted(repo.getName()))
                .header(AUTHORIZATION, this.adminBearerToken())),
        "imageDeleted",
        "Image deleted.");
    assertThat(this.manifestFiles(repo)).as("no image has the manifest any more").isZero();
    assertThat(this.netUsage(repo)).isEqualTo(-bytes(manifest).length);
  }

  @Test
  @DisplayName("deleting the repo removes its images with their manifests, tags and index edges")
  void repoDeleteRemovesTheContentAddressedRows() throws Exception {
    final var repo = this.dockerRepo();
    final var child =
        this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-one"))), "layer-one");
    this.push(repo, IMAGE, "latest", "layer-two");
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, index(child))
                .getStatus())
        .isEqualTo(201);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(3);
    // The request deletes the layers too; do not keep manifests that reference them managed in
    // this test's own session, as the request itself would not.
    this.entityManager.flush();
    this.entityManager.clear();

    this.expectSuccess(
        this.perform(
            delete("/api/repos/%s".formatted(repo.getName()))
                .header(AUTHORIZATION, this.adminBearerToken())),
        "repoDeleted",
        "Repo deleted.");
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.manifestRepository.findAllByImageId(image.getId())).isEmpty();
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .isEmpty();
    assertThat(this.manifestChildRepository.count()).isZero();
  }
}
