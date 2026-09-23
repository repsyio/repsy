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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Disk usage the Docker layer upload endpoints report, through the real wire protocol.
 *
 * <p>A chunk is charged as it is written and a layer whose digest is already stored is dropped at
 * finalize, so a duplicate push has to hand its bytes back: the repo holds one copy of the layer
 * and must be charged for one. A layer stored before finalize renamed it can still be under its
 * layer UUID, and the manifest push drops it the same way and refunds it.
 *
 * <p>A finalize that carries the closing chunk in its body and then fails (a digest mismatch, for
 * example) still charges that chunk: bytes are settled centrally by the router once the handler
 * returns or throws, not only when it returns successfully (RPS-1114).
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage deltas the upload post-processor requests.
 */
@DisplayName("Docker layer upload usage")
class DockerLayerUploadUsageIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private LayerRepository layerRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static byte[] layerBytes(final String content) {
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

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
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
    requireStatus(start, 202, "layer upload start");

    final var location = start.getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  /** {@code PATCH}es the whole layer as the upload's body, like {@code docker push} does. */
  private void patchChunk(
      final Repo repo, final String uploadId, final byte[] bytes, final String token)
      throws Exception {
    this.patchChunkForRange(repo, uploadId, bytes, token);
  }

  /** {@code PATCH}es one chunk and answers the {@code Range} header of the 202 response. */
  private String patchChunkForRange(
      final Repo repo, final String uploadId, final byte[] bytes, final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(bytes)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 202, "layer chunk upload");
    return response.getHeader("Range");
  }

  /** Finalizes the upload; {@code body} is empty when the bytes went up in earlier chunks. */
  private void finalizeUpload(
      final Repo repo,
      final String uploadId,
      final String digest,
      final byte[] body,
      final String token)
      throws Exception {
    requireStatus(
        this.tryFinalizeUpload(repo, uploadId, digest, body, token), 201, "layer upload finalize");
  }

  private MockHttpServletResponse tryFinalizeUpload(
      final Repo repo,
      final String uploadId,
      final String digest,
      final byte[] body,
      final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                .param("digest", digest)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Pushes a layer the way {@code docker push} does: start, one chunk, empty finalize. */
  private void pushChunked(final Repo repo, final byte[] layer, final String token)
      throws Exception {
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, layer, token);
    this.finalizeUpload(repo, uploadId, sha256(layer), new byte[0], token);
  }

  private static String manifestJson(final byte[] config, final byte[] layer) {
    return """
        {"schemaVersion":2,"mediaType":"%s",\
        "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%d},\
        "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"%s","size":%d}]}"""
        .formatted(OCI_MANIFEST, sha256(config), config.length, sha256(layer), layer.length);
  }

  /**
   * Pushes a manifest through a {@link MockMvc} without the servlet filters: the manifest handler
   * matches the {@code Content-Type} header exactly, and the character-encoding filter would append
   * {@code ;charset=UTF-8} to it, which a real client (and Tomcat) never sends.
   */
  private void pushManifest(final Repo repo, final String manifest, final String token)
      throws Exception {
    final var response =
        MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
            .build()
            .perform(
                put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, "latest")
                    .contentType(OCI_MANIFEST)
                    .content(manifest.getBytes(StandardCharsets.UTF_8))
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 201, "manifest push");
  }

  /**
   * Stores {@code content} under the layer's UUID, the name a layer pushed before layers were
   * renamed at finalize still has on disk, and returns that file's name.
   */
  private String writeLegacyLayerFile(final Repo repo, final byte[] content) throws Exception {
    final var uuid =
        this.layerRepository
            .findByRepoIdAndDigest(repo.getId(), sha256(content))
            .orElseThrow()
            .getId();
    Files.write(storageDirOf(repo).resolve("blobs").resolve(uuid.toString()), content);
    return uuid.toString();
  }

  /** Pushes a layer in one request: start, then a finalize that carries the bytes. */
  private void pushMonolithic(final Repo repo, final byte[] layer, final String token)
      throws Exception {
    final var uploadId = this.startUpload(repo, token);
    this.finalizeUpload(repo, uploadId, sha256(layer), layer, token);
  }

  /** The sum of every disk-usage delta the repo was charged since the last reset. */
  private long netUsage(final Repo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  private long blobFilesIn(final Repo repo) throws Exception {
    final var blobs = storageDirOf(repo).resolve("blobs");

    try (final var files = Files.list(blobs)) {
      return files.count();
    }
  }

  @Test
  @DisplayName("a new layer is charged once, however many chunks carried it")
  void newLayerIsCharged() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var layer = layerBytes("layer-one-".repeat(50));

    this.pushChunked(repo, layer, token);

    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).exists();
    assertThat(this.blobFilesIn(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("pushing the same layer again charges nothing more and leaves no temp upload")
  void duplicateChunkedLayerIsRefunded() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var layer = layerBytes("shared-layer-".repeat(40));
    this.pushChunked(repo, layer, token);
    clearInvocations(this.usageUpdateService);

    this.pushChunked(repo, layer, token);

    // The second push's chunk is charged, then handed back when the copy is dropped.
    assertThat(this.netUsage(repo)).isZero();
    assertThat(this.blobFilesIn(repo)).isEqualTo(1);
    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).hasBinaryContent(layer);
  }

  @Test
  @DisplayName("a duplicate that arrives in the finalize request is netted out in that request")
  void duplicateMonolithicLayerReportsNothing() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var layer = layerBytes("monolithic-layer-".repeat(30));
    this.pushMonolithic(repo, layer, token);
    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
    clearInvocations(this.usageUpdateService);

    this.pushMonolithic(repo, layer, token);

    verifyNoInteractions(this.usageUpdateService);
    assertThat(this.blobFilesIn(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("distinct layers are each charged")
  void distinctLayersAreEachCharged() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var layers = List.of(layerBytes("first-".repeat(20)), layerBytes("second-".repeat(35)));

    for (final var layer : layers) {
      this.pushChunked(repo, layer, token);
    }
    // Same layers again, as a second image build would push them.
    for (final var layer : layers) {
      this.pushChunked(repo, layer, token);
    }

    assertThat(this.netUsage(repo)).isEqualTo(layers.stream().mapToLong(l -> l.length).sum());
    assertThat(this.blobFilesIn(repo)).isEqualTo(2);
  }

  @Test
  @DisplayName("a manifest push refunds the legacy UUID layer files it drops as duplicates")
  void manifestPushRefundsDroppedLegacyLayers() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var config = layerBytes("{\"architecture\":\"amd64\",\"os\":\"linux\"}");
    final var layer = layerBytes("legacy-layer-".repeat(60));
    this.pushChunked(repo, config, token);
    this.pushChunked(repo, layer, token);
    // Both blobs also sit under their layer UUID, as they did before finalize renamed them.
    final var configLegacyName = this.writeLegacyLayerFile(repo, config);
    final var layerLegacyName = this.writeLegacyLayerFile(repo, layer);
    final var manifest = manifestJson(config, layer);
    clearInvocations(this.usageUpdateService);

    this.pushManifest(repo, manifest, token);

    // The manifest is charged, and both dropped legacy copies (charged when they were uploaded)
    // are handed back in the same request.
    assertThat(this.netUsage(repo)).isEqualTo(manifest.length() - layer.length - config.length);
    assertThat(storageDirOf(repo).resolve("blobs").resolve(configLegacyName)).doesNotExist();
    assertThat(storageDirOf(repo).resolve("blobs").resolve(layerLegacyName)).doesNotExist();
    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).hasBinaryContent(layer);
    assertThat(this.blobFilesIn(repo)).isEqualTo(2);
  }

  @Test
  @DisplayName("a manifest push that only renames a legacy layer charges just the manifest")
  void manifestPushRenamingLegacyLayerRefundsNothing() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var config = layerBytes("{\"architecture\":\"arm64\",\"os\":\"linux\"}");
    final var layer = layerBytes("renamed-layer-".repeat(45));
    this.pushChunked(repo, config, token);
    this.pushChunked(repo, layer, token);
    final var blobs = storageDirOf(repo).resolve("blobs");
    // A layer stored before the rename at finalize: only the UUID file exists.
    final var layerLegacyName = this.writeLegacyLayerFile(repo, layer);
    Files.delete(blobs.resolve(sha256(layer)));
    final var manifest = manifestJson(config, layer);
    clearInvocations(this.usageUpdateService);

    this.pushManifest(repo, manifest, token);

    assertThat(this.netUsage(repo)).isEqualTo(manifest.length());
    assertThat(blobs.resolve(layerLegacyName)).doesNotExist();
    assertThat(blobs.resolve(sha256(layer))).hasBinaryContent(layer);
    assertThat(this.blobFilesIn(repo)).isEqualTo(2);
  }

  @Test
  @DisplayName("a layer sent in several chunks is stored whole and answers the running range")
  void layerSentInSeveralChunksIsStoredWhole() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var chunks =
        List.of(layerBytes("aaaa".repeat(25)), layerBytes("bbbb".repeat(40)), layerBytes("cc"));
    final var layer = concat(chunks);
    final var uploadId = this.startUpload(repo, token);

    long sent = 0;
    for (final var chunk : chunks) {
      sent += chunk.length;
      assertThat(this.patchChunkForRange(repo, uploadId, chunk, token))
          .isEqualTo("0-" + (sent - 1));
    }
    this.finalizeUpload(repo, uploadId, sha256(layer), new byte[0], token);

    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).hasBinaryContent(layer);
    assertThat(this.blobFilesIn(repo)).isEqualTo(1);
    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
    assertThat(this.layerRepository.findByRepoIdAndDigest(repo.getId(), sha256(layer)))
        .hasValueSatisfying(stored -> assertThat(stored.getSize()).isEqualTo(layer.length));
  }

  @Test
  @DisplayName("the chunk that closes the upload in the finalize request is appended to the rest")
  void closingChunkIsAppended() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var head = layerBytes("head-".repeat(30));
    final var tail = layerBytes("tail-".repeat(20));
    final var layer = concat(List.of(head, tail));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, head, token);

    this.finalizeUpload(repo, uploadId, sha256(layer), tail, token);

    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).hasBinaryContent(layer);
    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
  }

  @Test
  @DisplayName("a layer pushed again in several chunks is refunded down to the one stored copy")
  void duplicateMultiChunkLayerIsRefunded() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var chunks = List.of(layerBytes("one-".repeat(30)), layerBytes("two-".repeat(30)));
    final var layer = concat(chunks);
    for (int push = 0; push < 2; push++) {
      final var uploadId = this.startUpload(repo, token);
      for (final var chunk : chunks) {
        this.patchChunk(repo, uploadId, chunk, token);
      }
      this.finalizeUpload(repo, uploadId, sha256(layer), new byte[0], token);
    }

    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
    assertThat(this.blobFilesIn(repo)).isEqualTo(1);
    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).hasBinaryContent(layer);
  }

  @Test
  @DisplayName("a finalize whose digest does not match what was uploaded is refused")
  void digestMismatchIsRefused() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var first = layerBytes("first-half-".repeat(20));
    final var second = layerBytes("second-half-".repeat(20));
    final var claimed = sha256(layerBytes("what the client believes it sent"));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, first, token);
    this.patchChunk(repo, uploadId, second, token);

    final var response = this.tryFinalizeUpload(repo, uploadId, claimed, new byte[0], token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("DIGEST_INVALID");
    assertThat(this.layerRepository.findByRepoIdAndDigest(repo.getId(), claimed)).isEmpty();
    assertThat(storageDirOf(repo).resolve("blobs").resolve(claimed)).doesNotExist();
    // The upload is still what the client sent, and it stays charged until it is cleaned up.
    assertThat(storageDirOf(repo).resolve("blobs").resolve(uploadId))
        .hasBinaryContent(concat(List.of(first, second)));
    assertThat(this.netUsage(repo)).isEqualTo(first.length + second.length);
  }

  @Test
  @DisplayName(
      "a finalize with a closing chunk and a wrong digest still charges the whole upload"
          + " (RPS-1114)")
  void closingChunkWithWrongDigestIsStillCharged() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var head = layerBytes("head-".repeat(30));
    final var tail = layerBytes("tail-".repeat(20));
    final var layer = concat(List.of(head, tail));
    final var claimed = sha256(layerBytes("what the client believes it sent"));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, head, token);

    final var response = this.tryFinalizeUpload(repo, uploadId, claimed, tail, token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("DIGEST_INVALID");
    assertThat(this.layerRepository.findByRepoIdAndDigest(repo.getId(), claimed)).isEmpty();
    // The closing chunk carried in the failing finalize request was appended to the upload file,
    // and the file is still what the client sent, so the request's net usage is the whole file:
    // it must be charged even though the request itself failed, or the abandoned-upload cleanup
    // later releases bytes that were never charged in the first place.
    assertThat(storageDirOf(repo).resolve("blobs").resolve(uploadId)).hasBinaryContent(layer);
    assertThat(this.netUsage(repo)).isEqualTo(layer.length);
  }

  @Test
  @DisplayName("a chunk sent twice makes the digest check refuse the finalize")
  void resentChunkFailsTheDigestCheck() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.dockerRepo();
    final var layer = layerBytes("resent-layer-".repeat(30));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, layer, token);
    this.patchChunk(repo, uploadId, layer, token);

    final var response = this.tryFinalizeUpload(repo, uploadId, sha256(layer), new byte[0], token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(storageDirOf(repo).resolve("blobs").resolve(sha256(layer))).doesNotExist();
  }

  private static byte[] concat(final List<byte[]> chunks) {
    final var out = new ByteArrayOutputStream();
    chunks.forEach(out::writeBytes);
    return out.toByteArray();
  }
}
