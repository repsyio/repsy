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
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Disk usage the Docker layer upload endpoints report, through the real wire protocol.
 *
 * <p>A chunk is charged as it is written and a layer whose digest is already stored is dropped at
 * finalize, so a duplicate push has to hand its bytes back: the repo holds one copy of the layer
 * and must be charged for one.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage deltas the upload post-processor requests.
 */
@DisplayName("Docker layer upload usage")
class DockerLayerUploadUsageIT extends AbstractIntegrationTest {

  private static final int PROTOCOL_PORT = 9090;
  private static final String IMAGE = "app";

  @MockitoBean private UsageUpdateService usageUpdateService;

  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

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
  }

  /** Finalizes the upload; {@code body} is empty when the bytes went up in earlier chunks. */
  private void finalizeUpload(
      final Repo repo,
      final String uploadId,
      final String digest,
      final byte[] body,
      final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(body)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 201, "layer upload finalize");
  }

  /** Pushes a layer the way {@code docker push} does: start, one chunk, empty finalize. */
  private void pushChunked(final Repo repo, final byte[] layer, final String token)
      throws Exception {
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, layer, token);
    this.finalizeUpload(repo, uploadId, sha256(layer), new byte[0], token);
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
}
