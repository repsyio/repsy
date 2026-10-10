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
import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService;
import io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService.BackfillReport;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1563: a release before RPS-1216 never filled {@code docker_image.size} and {@code
 * docker_image.digest}, so after an upgrade such an image lists size 0 and no digest in the panel
 * until it is pushed again. The legacy state is made from an image pushed through the wire
 * protocol, whose row is then zeroed the way that release left it, exactly what {@link
 * DockerImageStatsBackfillService} must undo without touching an image that already carries its
 * size and digest.
 */
@DisplayName("Docker image stats backfill (RPS-1563)")
class DockerImageStatsBackfillIT extends AbstractIT {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private DockerImageStatsBackfillService backfillService;

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

  /** Pushes an image manifest under {@code tag} and answers its JSON. */
  private String push(final Repo repo, final String tag, final String layer) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = imageManifest(layer);
    assertThat(this.wire.putImage(repo, IMAGE, tag, manifest).getStatus()).isEqualTo(201);

    return manifest;
  }

  /** Zeroes size and clears digest the way a release before RPS-1216 left a pushed image. */
  private void makeLegacy(final Repo repo) {
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    // The bulk update the push already used to fill size and digest, called with the values a
    // release before RPS-1216 always left: it bypasses the entity that the push's own read left in
    // the session, whose cached size and digest a plain setter-and-flush cannot overwrite.
    this.imageRepository.updateImageSizeAndDigest(
        repo.getId(), image.getId(), null, 0L, Instant.now());
    this.entityManager.clear();
  }

  /**
   * Always clears the persistence context first: the backfill (like the layout repair) runs in a
   * transaction of its own, on rows it loads itself, and writes them with a bulk update that a
   * plain repository read already cached in this test's session would not see otherwise.
   */
  private Image reload(final Repo repo) {
    this.entityManager.clear();
    return this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
  }

  @Test
  @DisplayName("fills the size and digest of a tagged image an earlier release left at 0 and null")
  void backfillsALegacyImage() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "latest", "layer-one");
    final var digest = sha256(bytes(manifest));
    final var expectedSize = this.reload(repo).getSize();
    this.makeLegacy(repo);
    assertThat(this.reload(repo).getSize()).isZero();
    assertThat(this.reload(repo).getDigest()).isNull();

    final var report = this.backfillService.backfill();

    assertThat(report.refreshed()).isGreaterThanOrEqualTo(1);
    assertThat(report.failed()).isZero();
    final var refreshed = this.reload(repo);
    assertThat(refreshed.getDigest()).isEqualTo(digest);
    assertThat(refreshed.getSize()).isEqualTo(expectedSize).isPositive();
  }

  @Test
  @DisplayName("a rerun does nothing once every legacy image is filled")
  void isIdempotent() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "latest", "layer-one");
    this.makeLegacy(repo);

    final var first = this.backfillService.backfill();
    assertThat(first.refreshed()).isGreaterThanOrEqualTo(1);

    assertThat(this.backfillService.backfill()).isEqualTo(new BackfillReport(0, 0));
  }

  @Test
  @DisplayName("an image that already has its size and digest is left exactly as it is")
  void doesNotTouchAnAlreadyCorrectImage() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "latest", "layer-one");
    final var before = this.reload(repo);

    final var report = this.backfillService.backfill();

    assertThat(report.isEmpty()).isTrue();
    final var after = this.reload(repo);
    assertThat(after.getSize()).isEqualTo(before.getSize());
    assertThat(after.getDigest()).isEqualTo(before.getDigest());
  }
}
