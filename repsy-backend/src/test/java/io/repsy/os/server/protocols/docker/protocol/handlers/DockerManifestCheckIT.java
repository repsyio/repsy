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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1215: {@code HEAD /v2/{repo}/{image}/manifests/{ref}} must answer exactly like {@code GET} of
 * the same reference (distribution HTTP API V2: "HEAD ... MUST be identical to GET ... except no
 * body is returned"), for both a tag and a digest reference. Before the fix, HEAD resolved a digest
 * reference through a tag-only lookup ({@code findTagAndManifest}) and a storage path derived from
 * the raw reference, so a digest GET served fine still 404'd on HEAD.
 */
@DisplayName("Docker manifest check (HEAD)")
class DockerManifestCheckIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
  private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

  @Autowired private WebApplicationContext webApplicationContext;

  private static String sha256(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private void pushBlob(final Repo repo, final byte[] blob, final String token) throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .param("digest", sha256(blob))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).isEqualTo(201);
  }

  /**
   * Sends a manifest through {@link org.springframework.test.web.servlet.MockMvc} without the
   * servlet filters: the manifest handler matches the {@code Content-Type} header exactly, and the
   * character-encoding filter would append {@code ;charset=UTF-8} to it, which a real client never
   * sends.
   */
  private MockHttpServletResponse putManifest(
      final Repo repo,
      final String reference,
      final String contentType,
      final String body,
      final String token)
      throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .contentType(contentType)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse headManifest(
      final Repo repo, final String reference, final String token) throws Exception {
    return this.mockMvc
        .perform(
            head("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse getManifest(
      final Repo repo, final String reference, final String token) throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private record PushedManifest(String digest, String mediaType) {}

  /** Pushes a minimal, single-layer OCI image manifest under {@code reference} and returns it. */
  private PushedManifest pushImageManifest(
      final Repo repo, final String reference, final String token) throws Exception {
    final var config =
        ("{\"architecture\":\"amd64\",\"os\":\"linux\"}" + reference)
            .getBytes(StandardCharsets.UTF_8);
    final var layer = ("layer-content-" + reference).getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
            .formatted(
                OCI_MANIFEST,
                OCI_CONFIG,
                sha256(config),
                config.length,
                OCI_LAYER,
                sha256(layer),
                layer.length);

    final var response = this.putManifest(repo, reference, OCI_MANIFEST, manifest, token);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return new PushedManifest(sha256(manifest.getBytes(StandardCharsets.UTF_8)), OCI_MANIFEST);
  }

  @Test
  @DisplayName("HEAD by tag is 200 with the same headers a GET of the same tag answers")
  void headByTagMirrorsGet() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var pushed = this.pushImageManifest(repo, "latest", token);

    final var head = this.headManifest(repo, "latest", token);
    final var get = this.getManifest(repo, "latest", token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(pushed.digest());
    assertThat(head.getContentType()).isEqualTo(pushed.mediaType());
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Docker-Content-Digest"))
        .isEqualTo(get.getHeader("Docker-Content-Digest"));
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName(
      "HEAD by digest is 200 with the same headers a GET of the same digest answers -- the"
          + " RPS-1215 regression pin")
  void headByDigestMirrorsGet() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var pushed = this.pushImageManifest(repo, "latest", token);

    final var head = this.headManifest(repo, pushed.digest(), token);
    final var get = this.getManifest(repo, pushed.digest(), token);

    assertThat(head.getStatus()).as(head.getContentAsString()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(pushed.digest());
    assertThat(head.getContentType()).isEqualTo(pushed.mediaType());
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Docker-Content-Digest"))
        .isEqualTo(get.getHeader("Docker-Content-Digest"));
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName("HEAD of an unknown digest or an unknown tag is 404")
  void headOfUnknownReferenceIs404() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushImageManifest(repo, "latest", token);

    final var unknownDigest = this.headManifest(repo, "sha256:" + "0".repeat(64), token);
    final var unknownTag = this.headManifest(repo, "no-such-tag", token);

    assertThat(unknownDigest.getStatus()).isEqualTo(404);
    assertThat(unknownTag.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "HEAD of a multi-arch index's per-platform child by its own digest is 200, even though that"
          + " manifest has no Tag row of its own -- this could never have worked before RPS-1215")
  void headOfMultiPlatformChildByItsOwnDigest() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
            .formatted(
                OCI_MANIFEST,
                OCI_CONFIG,
                sha256(config),
                config.length,
                OCI_LAYER,
                sha256(layer),
                layer.length);

    // Pushed directly by digest: per TagForm.isSinglePlatformByTagName(), this creates no Tag row
    // (only the later index push below creates the Manifest row, keyed by digest).
    final var manifestDigest = sha256(manifest.getBytes(StandardCharsets.UTF_8));
    final var platformPush = this.putManifest(repo, manifestDigest, OCI_MANIFEST, manifest, token);
    assertThat(platformPush.getStatus()).isEqualTo(201);

    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}"
            .formatted(OCI_INDEX, OCI_MANIFEST, manifestDigest, manifest.length());
    final var indexPush = this.putManifest(repo, "multi", OCI_INDEX, index, token);
    assertThat(indexPush.getStatus()).isEqualTo(201);

    final var head = this.headManifest(repo, manifestDigest, token);

    assertThat(head.getStatus()).as(head.getContentAsString()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(manifestDigest);
  }
}
