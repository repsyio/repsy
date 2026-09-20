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
package io.repsy.os.server.protocols.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Abandoned Docker and Helm OCI blob uploads, through the real wire protocol (RPS-1041).
 *
 * <p>An upload that is started and never finalized leaves its temp file on disk with its bytes
 * charged to the repo. The cleanup has to delete such a file once it has been idle longer than the
 * TTL and hand its bytes back, and it must leave finalized blobs and uploads that are still in
 * progress alone.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage deltas the uploads and the cleanup request.
 */
@DisplayName("Abandoned blob upload cleanup")
class AbandonedBlobUploadCleanupIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final Duration IDLE_FOR_TWO_DAYS = Duration.ofDays(2);

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private AbandonedBlobUploadCleanupService cleanupService;
  @Autowired private LayerRepository layerRepository;
  @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;

  private static byte[] bytes(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void requireStatus(
      final MockHttpServletResponse response, final int expected, final String step) {
    if (response.getStatus() != expected) {
      throw new IllegalStateException(
          "%s answered %d instead of %d".formatted(step, response.getStatus(), expected));
    }
  }

  private static void makeIdle(final Path file) throws IOException {
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(IDLE_FOR_TWO_DAYS)));
  }

  private String startUpload(final Repo repo, final String token) throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(start, 202, "blob upload start");

    final var location = start.getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void patchChunk(
      final Repo repo, final String uploadId, final byte[] chunk, final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(chunk)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 202, "blob chunk upload");
  }

  private void finalizeUpload(
      final Repo repo, final String uploadId, final String digest, final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .param("digest", digest)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 201, "blob upload finalize");
  }

  /** Starts an upload and sends one chunk, then walks away like a crashed client. */
  private String abandonUpload(final Repo repo, final byte[] chunk, final String token)
      throws Exception {
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, chunk, token);
    return uploadId;
  }

  /** The sum of every disk-usage delta the repo was charged or refunded. */
  private long netUsage(final Repo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  @Test
  @DisplayName("the cleanup is registered with the scheduler, with the configured durations")
  void cleanupIsScheduled() {
    final var registered =
        this.scheduledTasks.getScheduledTasks().stream()
            .filter(task -> task.getTask().toString().contains("AbandonedBlobUploadCleanupTask"))
            .toList();

    assertThat(registered).hasSize(1);
  }

  @Test
  @DisplayName("Docker: an idle unfinished upload is deleted and its bytes are released")
  void dockerAbandonedUploadIsDeletedAndReleased() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var chunk = bytes("half-pushed-layer-".repeat(30));
    final var uploadId = this.abandonUpload(repo, chunk, token);
    final var tempFile = storageDirOf(repo).resolve("blobs").resolve(uploadId);
    assertThat(this.netUsage(repo)).isEqualTo(chunk.length);
    makeIdle(tempFile);

    final var released = this.cleanupService.cleanupAbandonedUploads();

    assertThat(released).isGreaterThanOrEqualTo(chunk.length);
    assertThat(tempFile).doesNotExist();
    assertThat(this.netUsage(repo)).isZero();
  }

  @Test
  @DisplayName("Docker: an upload that is still receiving data is left alone")
  void dockerInProgressUploadIsKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var chunk = bytes("in-progress-layer-".repeat(30));
    final var uploadId = this.abandonUpload(repo, chunk, token);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(storageDirOf(repo).resolve("blobs").resolve(uploadId)).hasBinaryContent(chunk);
    assertThat(this.netUsage(repo)).isEqualTo(chunk.length);
  }

  @Test
  @DisplayName("Docker: a finalized layer is left alone however old it is")
  void dockerFinalizedLayerIsKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var layer = bytes("finished-layer-".repeat(30));
    final var uploadId = this.abandonUpload(repo, layer, token);
    this.finalizeUpload(repo, uploadId, sha256(layer), token);
    final var blob = storageDirOf(repo).resolve("blobs").resolve(sha256(layer));
    makeIdle(blob);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(blob).hasBinaryContent(layer);
    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
  }

  @Test
  @DisplayName("Docker: a layer stored under the id of its layer row is left alone")
  void dockerLayerStoredUnderItsRowIdIsKept() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var layer = new Layer();
    layer.setRepo(this.repoRepository.findById(repo.getId()).orElseThrow());
    layer.setDigest(sha256(bytes("legacy-layer")));
    layer.setMediaType("application/vnd.docker.image.rootfs.diff.tar.gzip");
    layer.setSize(12);
    final var saved = this.layerRepository.saveAndFlush(layer);
    final var legacyFile = storageDirOf(repo).resolve("blobs").resolve(saved.getId().toString());
    Files.createDirectories(legacyFile.getParent());
    Files.write(legacyFile, bytes("legacy-layer"));
    makeIdle(legacyFile);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(legacyFile).exists();
    assertThat(this.netUsage(repo)).isZero();
  }

  @Test
  @DisplayName("Helm OCI: an idle unfinished upload is deleted and its bytes are released")
  void helmAbandonedUploadIsDeletedAndReleased() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chunk = bytes("half-pushed-chart-".repeat(30));
    final var uploadId = this.abandonUpload(repo, chunk, token);
    final var tempFile = storageDirOf(repo).resolve("oci").resolve("blobs").resolve(uploadId);
    assertThat(this.netUsage(repo)).isEqualTo(chunk.length);
    makeIdle(tempFile);

    final var released = this.cleanupService.cleanupAbandonedUploads();

    assertThat(released).isGreaterThanOrEqualTo(chunk.length);
    assertThat(tempFile).doesNotExist();
    assertThat(this.netUsage(repo)).isZero();
  }

  @Test
  @DisplayName("Helm OCI: in-progress uploads and finalized blobs are left alone")
  void helmInProgressAndFinalizedBlobsAreKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chunk = bytes("in-progress-chart-".repeat(30));
    final var inProgressId = this.abandonUpload(repo, chunk, token);
    final var blob = bytes("finished-chart-".repeat(30));
    final var finishedId = this.abandonUpload(repo, blob, token);
    this.finalizeUpload(repo, finishedId, sha256(blob), token);
    final var blobsDir = storageDirOf(repo).resolve("oci").resolve("blobs");
    makeIdle(blobsDir.resolve(sha256(blob)));

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(blobsDir.resolve(inProgressId)).hasBinaryContent(chunk);
    assertThat(blobsDir.resolve(sha256(blob))).hasBinaryContent(blob);
    assertThat(this.netUsage(repo)).isEqualTo((long) chunk.length + blob.length);
  }
}
