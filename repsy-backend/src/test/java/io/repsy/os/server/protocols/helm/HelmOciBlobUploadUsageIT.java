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
package io.repsy.os.server.protocols.helm;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Disk usage the Helm OCI blob upload endpoints report, through the real wire protocol — the Helm
 * equivalent of {@link
 * io.repsy.os.server.protocols.docker.protocol.handlers.DockerLayerUploadUsageIT}, since Helm's OCI
 * routes reuse the same {@code /v2/...} blob-upload protocol Docker does.
 *
 * <p>A finalize that carries the closing chunk in its body and then fails (a digest mismatch, for
 * example) still charges that chunk: bytes are settled centrally by the router once the handler
 * returns or throws, not only when it returns successfully (RPS-1114).
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage deltas the upload post-processor requests.
 */
@DisplayName("Helm OCI blob upload usage")
class HelmOciBlobUploadUsageIT extends AbstractIntegrationTest {

  private static final String CHART = "app";

  @MockitoBean private UsageUpdateService usageUpdateService;

  private static byte[] blobBytes(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static void requireStatus(
      final MockHttpServletResponse response, final int expected, final String step) {
    if (response.getStatus() != expected) {
      throw new IllegalStateException(
          "%s answered %d instead of %d".formatted(step, response.getStatus(), expected));
    }
  }

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private String startUpload(final Repo repo, final String token) throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), CHART)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(start, 202, "blob upload start");

    final var location = start.getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void patchChunk(
      final Repo repo, final String uploadId, final byte[] bytes, final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(bytes)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    requireStatus(response, 202, "blob chunk upload");
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
            put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                .param("digest", digest)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void finalizeUpload(
      final Repo repo,
      final String uploadId,
      final String digest,
      final byte[] body,
      final String token)
      throws Exception {
    requireStatus(
        this.tryFinalizeUpload(repo, uploadId, digest, body, token), 201, "blob upload finalize");
  }

  /** Pushes a blob the way {@code helm push} does: start, one chunk, empty finalize. */
  private void pushChunked(final Repo repo, final byte[] blob, final String token)
      throws Exception {
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, blob, token);
    this.finalizeUpload(repo, uploadId, digest("SHA-256", blob), new byte[0], token);
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

  private static byte[] concat(final List<byte[]> chunks) {
    final var out = new ByteArrayOutputStream();
    chunks.forEach(out::writeBytes);
    return out.toByteArray();
  }

  @Test
  @DisplayName("a new blob is charged once, however many chunks carried it")
  void newBlobIsCharged() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo();
    final var blob = blobBytes("layer-one-".repeat(50));

    this.pushChunked(repo, blob, token);

    assertThat(this.netUsage(repo)).isEqualTo(blob.length);
  }

  @Test
  @DisplayName("the chunk that closes the upload in the finalize request is appended to the rest")
  void closingChunkIsAppended() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo();
    final var head = blobBytes("head-".repeat(30));
    final var tail = blobBytes("tail-".repeat(20));
    final var blob = concat(List.of(head, tail));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, head, token);

    this.finalizeUpload(repo, uploadId, digest("SHA-256", blob), tail, token);

    assertThat(this.netUsage(repo)).isEqualTo(blob.length);
  }

  @Test
  @DisplayName("a finalize whose digest does not match what was uploaded is refused")
  void digestMismatchIsRefused() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo();
    final var blob = blobBytes("first-half-".repeat(20));
    final var claimed = digest("SHA-256", blobBytes("what the client believes it sent"));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, blob, token);

    final var response = this.tryFinalizeUpload(repo, uploadId, claimed, new byte[0], token);

    assertThat(response.getStatus()).isEqualTo(400);
    // The chunk was already charged when it was PATCHed, before this failing finalize.
    assertThat(this.netUsage(repo)).isEqualTo(blob.length);
  }

  @Test
  @DisplayName(
      "a finalize with a closing chunk and a wrong digest still charges the whole upload"
          + " (RPS-1114)")
  void closingChunkWithWrongDigestIsStillCharged() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo();
    final var head = blobBytes("head-".repeat(30));
    final var tail = blobBytes("tail-".repeat(20));
    final var blob = concat(List.of(head, tail));
    final var claimed = digest("SHA-256", blobBytes("what the client believes it sent"));
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, head, token);

    final var response = this.tryFinalizeUpload(repo, uploadId, claimed, tail, token);

    assertThat(response.getStatus()).isEqualTo(400);
    // The closing chunk carried in the failing finalize request was appended to the upload file,
    // and the file is still what the client sent, so the request's net usage is the whole file:
    // it must be charged even though the request itself failed, or the abandoned-upload cleanup
    // later releases bytes that were never charged in the first place.
    assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(uploadId))
        .hasBinaryContent(blob);
    assertThat(this.netUsage(repo)).isEqualTo(blob.length);
  }
}
