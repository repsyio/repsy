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
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.DockerManifestLayoutRepairService;
import io.repsy.os.server.protocols.docker.shared.tag.services.DockerManifestLayoutRepairService.RepairReport;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1216: the manifests an earlier version stored keep their file under a name generated from the
 * tag ({@code docker_manifest.storage_name}) until {@link DockerManifestLayoutRepairService} moves
 * it to {@code manifests/<digest>} and fills the row's {@code sha512} digest. The registry serves
 * them meanwhile, and a rerun of the service has nothing to do.
 *
 * <p>The legacy state is made from a manifest pushed through the wire protocol: its file is moved
 * to the legacy name and its row gets that name and loses its {@code sha512} digest, exactly what
 * the database migration leaves behind.
 */
@DisplayName("Docker manifest layout repair (RPS-1216)")
class DockerManifestLayoutRepairIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private DockerManifestLayoutRepairService repairService;
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

  private Path manifestFile(final Repo repo, final String name) {
    return storageDirOf(repo).resolve("manifests").resolve(name);
  }

  private String legacyName(final Repo repo, final String reference) {
    return ManifestNameGenerator.generate(repo.getId(), IMAGE, reference);
  }

  /** Pushes an image manifest under {@code tag} and answers its JSON. */
  private String push(final Repo repo, final String tag, final String layer) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = imageManifest(layer);
    assertThat(this.wire.putImage(repo, IMAGE, tag, manifest).getStatus()).isEqualTo(201);

    return manifest;
  }

  /** Turns the stored manifest into what an earlier version left: legacy file name, no sha512. */
  private void makeLegacy(final Repo repo, final String manifest, final String reference)
      throws Exception {
    final var digest = sha256(bytes(manifest));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var row =
        this.manifestRepository.findByImageIdAndDigest(image.getId(), digest).orElseThrow();
    row.setStorageName(reference);
    row.setDigestSha512(null);
    this.manifestRepository.saveAndFlush(row);
    Files.move(
        this.manifestFile(repo, digest), this.manifestFile(repo, this.legacyName(repo, reference)));
    // The repair runs in a transaction of its own, on rows it loads itself, not on the entities
    // this test's pushes left in the session (an image created by a push points at a bare Repo).
    this.entityManager.clear();
  }

  private boolean sha512Of(final Repo repo, final String manifest) {
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();

    return sha512(bytes(manifest))
        .equals(
            this.manifestRepository
                .findByImageIdAndDigest(image.getId(), sha256(bytes(manifest)))
                .orElseThrow()
                .getDigestSha512());
  }

  private String storageNameOf(final Repo repo, final String manifest) {
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();

    return this.manifestRepository
        .findByImageIdAndDigest(image.getId(), sha256(bytes(manifest)))
        .orElseThrow()
        .getStorageName();
  }

  @Test
  @DisplayName(
      "a manifest with a legacy file name is served by tag, digest and the panel meanwhile")
  void legacyManifestIsServedBeforeTheJobRuns() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    this.makeLegacy(repo, manifest, "latest");
    final var digest = sha256(bytes(manifest));

    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(repo, IMAGE, digest).getContentAsString()).isEqualTo(manifest);
    assertThat(this.wire.headManifest(repo, IMAGE, digest).getStatus()).isEqualTo(200);
    assertThat(this.wire.getManifest(repo, IMAGE, digest).getContentAsString()).isEqualTo(manifest);
    final var panel =
        this.expectSuccess(
            this.perform(
                get("/api/docker/images/%s/%s/manifests/%s"
                        .formatted(repo.getName(), IMAGE, "latest"))
                    .header(AUTHORIZATION, this.adminBearerToken())),
            "manifestFetched",
            "Manifest fetched.");
    assertThat(panel).contains("schemaVersion");
  }

  @Test
  @DisplayName("the repair renames the file to the digest, fills sha512, and a rerun does nothing")
  void repairRenamesFillsAndIsIdempotent() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    this.makeLegacy(repo, manifest, "latest");
    final var digest = sha256(bytes(manifest));
    final var legacy = this.manifestFile(repo, this.legacyName(repo, "latest"));
    assertThat(legacy).exists();

    final var first = this.repairService.repair();

    assertThat(first.repaired()).isGreaterThanOrEqualTo(1);
    assertThat(first.unresolved()).isZero();
    assertThat(first.failed()).isZero();
    assertThat(legacy).doesNotExist();
    assertThat(this.manifestFile(repo, digest)).hasContent(manifest);
    assertThat(this.storageNameOf(repo, manifest)).isNull();
    assertThat(this.sha512Of(repo, manifest)).isTrue();
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(repo, IMAGE, sha512(bytes(manifest))).getStatus())
        .isEqualTo(200);

    assertThat(this.repairService.repair()).isEqualTo(new RepairReport(0, 0, 0));
    assertThat(this.manifestFile(repo, digest)).hasContent(manifest);
  }

  @Test
  @DisplayName("a legacy file that a re-push made redundant is dropped and its bytes are released")
  void redundantLegacyFileIsDroppedAndRefunded() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    final var digest = sha256(bytes(manifest));
    this.makeLegacy(repo, manifest, "latest");
    // The upgraded registry stored the same bytes at the digest name when the manifest was pushed
    // again.
    Files.writeString(this.manifestFile(repo, digest), manifest);
    clearInvocations(this.usageUpdateService);

    final var report = this.repairService.repair();

    assertThat(report.repaired()).isGreaterThanOrEqualTo(1);
    assertThat(this.manifestFile(repo, this.legacyName(repo, "latest"))).doesNotExist();
    assertThat(this.manifestFile(repo, digest)).hasContent(manifest);
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(1)).updateUsage(captor.capture());
    assertThat(
            captor.getAllValues().stream()
                .filter(info -> info.repoId().equals(repo.getId()))
                .mapToLong(info -> info.usages().getDiskUsage())
                .sum())
        .isEqualTo(-bytes(manifest).length);
  }

  @Test
  @DisplayName("the file is found under the name of another tag that points at the digest")
  void fileIsFoundUnderAnotherTagName() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "tag-a", "layer-one");
    this.push(repo, "tag-b", "layer-one");
    // The old code could leave the row named after one tag while the bytes were written under the
    // other tag's name.
    this.makeLegacy(repo, manifest, "tag-a");
    Files.move(
        this.manifestFile(repo, this.legacyName(repo, "tag-a")),
        this.manifestFile(repo, this.legacyName(repo, "tag-b")));

    final var report = this.repairService.repair();

    assertThat(report.repaired()).isGreaterThanOrEqualTo(1);
    assertThat(report.unresolved()).isZero();
    assertThat(this.manifestFile(repo, sha256(bytes(manifest)))).hasContent(manifest);
    assertThat(this.storageNameOf(repo, manifest)).isNull();
  }

  @Test
  @DisplayName(
      "the other legacy copies of the same bytes, whose rows the migration folded in, are dropped"
          + " and their bytes released; a file with other content under such a name stays")
  void redundantLegacyCopiesAreDroppedAndRefunded() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "tag-a", "layer-one");
    this.push(repo, "tag-b", "layer-one");
    final var digest = sha256(bytes(manifest));
    this.makeLegacy(repo, manifest, "tag-a");
    // What the old code stored: the same bytes once per reference the manifest was pushed under.
    final var underTagB = this.manifestFile(repo, this.legacyName(repo, "tag-b"));
    final var underDigest = this.manifestFile(repo, this.legacyName(repo, digest));
    Files.writeString(underTagB, manifest);
    Files.writeString(underDigest, manifest);
    final var unrelated = this.manifestFile(repo, this.legacyName(repo, sha512(bytes(manifest))));
    Files.writeString(unrelated, "not this manifest");
    clearInvocations(this.usageUpdateService);

    final var report = this.repairService.repair();

    assertThat(report.repaired()).isGreaterThanOrEqualTo(1);
    assertThat(underTagB).doesNotExist();
    assertThat(underDigest).doesNotExist();
    assertThat(unrelated).hasContent("not this manifest");
    assertThat(this.manifestFile(repo, digest)).hasContent(manifest);
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(1)).updateUsage(captor.capture());
    assertThat(
            captor.getAllValues().stream()
                .filter(info -> info.repoId().equals(repo.getId()))
                .mapToLong(info -> info.usages().getDiskUsage())
                .sum())
        .isEqualTo(-2L * bytes(manifest).length);
  }

  @Test
  @DisplayName("a legacy file that does not hold the manifest is left alone and reported")
  void mismatchedFileIsLeftAlone() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    this.makeLegacy(repo, manifest, "latest");
    final var legacy = this.manifestFile(repo, this.legacyName(repo, "latest"));
    Files.writeString(legacy, "something else entirely");

    final var report = this.repairService.repair();

    assertThat(report.unresolved()).isEqualTo(1);
    assertThat(report.repaired()).isZero();
    assertThat(legacy).hasContent("something else entirely");
    assertThat(this.storageNameOf(repo, manifest)).isEqualTo("latest");
    assertThat(this.sha512Of(repo, manifest)).isFalse();
    assertThat(this.manifestFile(repo, sha256(bytes(manifest)))).doesNotExist();
  }

  @Test
  @DisplayName("a manifest whose file is missing under every name is reported and stays as it is")
  void missingFileIsReported() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    this.makeLegacy(repo, manifest, "latest");
    Files.delete(this.manifestFile(repo, this.legacyName(repo, "latest")));

    final var report = this.repairService.repair();

    assertThat(report.unresolved()).isEqualTo(1);
    assertThat(this.storageNameOf(repo, manifest)).isEqualTo("latest");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus()).isEqualTo(404);
  }
}
