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

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_CONFIG_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_LAYER_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_MANIFEST_TYPE;
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
import io.repsy.os.server.protocols.helm.HelmChartFixtures;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
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
 * Abandoned Docker and Helm OCI blob uploads, through the real wire protocol (RPS-1041), and the
 * finalized-but-unreferenced blobs the same sweep now also collects (RPS-1112, RPS-1172).
 *
 * <p>An upload that is started and never finalized leaves its temp file on disk with its bytes
 * charged to the repo. The cleanup has to delete such a file once it has been idle longer than the
 * TTL and hand its bytes back, and it must leave finalized blobs and uploads that are still in
 * progress alone — unless the finalized blob itself ended up referenced by nothing, in which case
 * it is now swept too, once it is stale.
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
  @Autowired private HelmOciBlobRepository helmOciBlobRepository;
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

  /**
   * Puts a Helm OCI manifest referencing {@code layerDigest} as its single layer, under {@code
   * name}/{@code reference}.
   */
  private MockHttpServletResponse putHelmManifest(
      final Repo repo,
      final String name,
      final String reference,
      final byte[] chartBytes,
      final String layerDigest,
      final String token)
      throws Exception {
    final var config = bytes("{}");
    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST_TYPE,
                OCI_CONFIG_TYPE,
                sha256(config),
                config.length,
                OCI_LAYER_TYPE,
                layerDigest,
                chartBytes.length);

    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, reference)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
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
  @DisplayName("Docker: a finalized layer with its row intact is left alone however old it is")
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
  @DisplayName("Docker: a stale digest blob with no layer row is swept (RPS-1172)")
  void dockerBlobWithNoLayerRowIsSwept() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var content = bytes("orphaned-blob-with-no-row-".repeat(10));
    final var digest = sha256(content);
    final var blob = storageDirOf(repo).resolve("blobs").resolve(digest);
    Files.createDirectories(blob.getParent());
    Files.write(blob, content);
    makeIdle(blob);
    assertThat(this.layerRepository.existsByRepoIdAndDigest(repo.getId(), digest)).isFalse();

    final var released = this.cleanupService.cleanupAbandonedUploads();

    assertThat(released).isGreaterThanOrEqualTo(content.length);
    assertThat(blob).doesNotExist();
  }

  @Test
  @DisplayName("Docker: a fresh digest blob with no layer row is kept, inside the TTL")
  void dockerFreshBlobWithNoLayerRowIsKept() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var content = bytes("fresh-orphaned-blob-".repeat(10));
    final var digest = sha256(content);
    final var blob = storageDirOf(repo).resolve("blobs").resolve(digest);
    Files.createDirectories(blob.getParent());
    Files.write(blob, content);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(blob).exists();
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
  @DisplayName("Helm OCI: an upload that is still receiving data is left alone")
  void helmInProgressUploadIsKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chunk = bytes("in-progress-chart-".repeat(30));
    final var uploadId = this.abandonUpload(repo, chunk, token);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(uploadId))
        .hasBinaryContent(chunk);
    assertThat(this.netUsage(repo)).isEqualTo(chunk.length);
  }

  @Test
  @DisplayName("Helm OCI: a finalized blob a manifest still references is kept however old it is")
  void helmBlobReferencedByManifestIsKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chartBytes = HelmChartFixtures.chart("keepme", "1.0.0");
    final var layerDigest = sha256(chartBytes);
    final var uploadId = this.abandonUpload(repo, chartBytes, token);
    this.finalizeUpload(repo, uploadId, layerDigest, token);

    final var accepted =
        this.putHelmManifest(repo, "keepme", "1.0.0", chartBytes, layerDigest, token);
    requireStatus(accepted, 201, "OCI manifest push");

    final var blob = storageDirOf(repo).resolve("oci").resolve("blobs").resolve(layerDigest);
    makeIdle(blob);

    this.cleanupService.cleanupAbandonedUploads();

    assertThat(blob).exists();
    assertThat(this.helmOciBlobRepository.findByRepoIdAndDigest(repo.getId(), layerDigest))
        .isPresent();
  }

  @Test
  @DisplayName(
      "Helm OCI: a blob whose manifest push is refused for a chart-name mismatch is swept, its"
          + " row too, and usage returns to baseline (RPS-1112)")
  void helmRefusedManifestPushBlobIsSweptWithItsRow() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chartBytes = HelmChartFixtures.chart("rightname", "1.0.0");
    final var layerDigest = sha256(chartBytes);
    final var uploadId = this.abandonUpload(repo, chartBytes, token);
    this.finalizeUpload(repo, uploadId, layerDigest, token);
    assertThat(this.netUsage(repo)).isEqualTo(chartBytes.length);

    // The path name ("wrongname") does not match the pushed chart's own Chart.yaml name
    // ("rightname"): AbstractHelmOciManifestPushProtocolMethodHandler#requireMatchingChartName
    // refuses it before any chart, chart-version or manifest row is ever created, leaving only the
    // already-finalized layer blob and its helm_oci_blob row behind.
    final var refused =
        this.putHelmManifest(repo, "wrongname", "1.0.0", chartBytes, layerDigest, token);
    requireStatus(refused, 400, "manifest push with mismatched chart name");

    final var blob = storageDirOf(repo).resolve("oci").resolve("blobs").resolve(layerDigest);
    assertThat(blob).exists();
    assertThat(this.helmOciBlobRepository.findByRepoIdAndDigest(repo.getId(), layerDigest))
        .isPresent();
    makeIdle(blob);

    final var released = this.cleanupService.cleanupAbandonedUploads();

    assertThat(released).isGreaterThanOrEqualTo(chartBytes.length);
    assertThat(blob).doesNotExist();
    assertThat(this.helmOciBlobRepository.findByRepoIdAndDigest(repo.getId(), layerDigest))
        .isEmpty();
    assertThat(this.netUsage(repo)).isZero();
  }

  @Test
  @DisplayName("Helm OCI: a fresh unreferenced finalized blob is kept, inside the TTL")
  void helmFreshUnreferencedBlobIsKept() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chartBytes = HelmChartFixtures.chart("nomanifest", "1.0.0");
    final var layerDigest = sha256(chartBytes);
    final var uploadId = this.abandonUpload(repo, chartBytes, token);
    this.finalizeUpload(repo, uploadId, layerDigest, token);

    this.cleanupService.cleanupAbandonedUploads();

    final var blob = storageDirOf(repo).resolve("oci").resolve("blobs").resolve(layerDigest);
    assertThat(blob).exists();
    assertThat(this.netUsage(repo)).isEqualTo(chartBytes.length);
  }
}
