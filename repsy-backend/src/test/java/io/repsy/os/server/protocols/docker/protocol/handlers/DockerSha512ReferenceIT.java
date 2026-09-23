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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1242: a blob or manifest referenced by a {@code sha512:} digest is routed like a {@code
 * sha256:} one. {@code BlobDigests.isSupported} (and so the push guards and the blob finalize)
 * accept both algorithms, but the path dispatch only recognized {@code sha256:}, so a sha512
 * reference was parsed as a tag and rejected.
 */
@DisplayName("Docker sha512 digest references")
class DockerSha512ReferenceIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

  @Autowired private WebApplicationContext webApplicationContext;

  private static String digest(final String algorithm, final String jcaName, final byte[] bytes) {
    try {
      return algorithm
          + ":"
          + HexFormat.of().formatHex(MessageDigest.getInstance(jcaName).digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(final byte[] bytes) {
    return digest("sha256", "SHA-256", bytes);
  }

  private static String sha512(final byte[] bytes) {
    return digest("sha512", "SHA-512", bytes);
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private void pushBlob(final Repo repo, final byte[] blob, final String digest, final String token)
      throws Exception {
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
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
    assertThat(finalize.getHeader("Docker-Content-Digest")).isEqualTo(digest);
  }

  /**
   * Sends a manifest without the servlet filters: the manifest handler matches the {@code
   * Content-Type} header exactly, and the character-encoding filter would append {@code
   * ;charset=UTF-8} to it.
   */
  private MockHttpServletResponse putManifest(
      final Repo repo, final String reference, final String body, final String token)
      throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .contentType(OCI_MANIFEST)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Pushes a config and a layer blob (sha256) and returns a manifest body that references them. */
  private String pushManifestBlobs(final Repo repo, final String token) throws Exception {
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, sha256(config), token);
    this.pushBlob(repo, layer, sha256(layer), token);

    return this.manifestBody(config, layer, sha256(layer));
  }

  private String manifestBody(final byte[] config, final byte[] layer, final String layerDigest) {
    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
        .formatted(
            OCI_MANIFEST,
            OCI_CONFIG,
            sha256(config),
            config.length,
            OCI_LAYER,
            layerDigest,
            layer.length);
  }

  private MockHttpServletResponse blobRequest(
      final boolean headOnly, final Repo repo, final String digest, final String token)
      throws Exception {
    final var path = "/v2/{repo}/{image}/blobs/{digest}";
    return this.mockMvc
        .perform(
            (headOnly
                    ? head(path, repo.getName(), IMAGE, digest)
                    : get(path, repo.getName(), IMAGE, digest))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse manifestRequest(
      final boolean headOnly, final Repo repo, final String reference, final String token)
      throws Exception {
    final var path = "/v2/{repo}/{image}/manifests/{reference}";
    return this.mockMvc
        .perform(
            (headOnly
                    ? head(path, repo.getName(), IMAGE, reference)
                    : get(path, repo.getName(), IMAGE, reference))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("HEAD of a blob by its digest is 200 with that digest and the blob's length")
  void headOfBlobByDigest(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = (algorithm + "-addressed-blob").getBytes(StandardCharsets.UTF_8);
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(repo, blob, digest, token);

    final var head = this.blobRequest(true, repo, digest, token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(digest);
    assertThat(head.getHeader("Content-Length")).isEqualTo(String.valueOf(blob.length));
  }

  @Test
  @DisplayName("HEAD of an unknown sha512 blob digest is 404")
  void unknownBlobShaFiveTwelveIs404() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();

    final var head = this.blobRequest(true, repo, "sha512:" + "0".repeat(128), token);

    assertThat(head.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a manifest pushed by its sha512 digest is served by HEAD and GET of that digest")
  void manifestByShaFiveTwelve() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = this.pushManifestBlobs(repo, token);
    final var reference = sha512(manifest.getBytes(StandardCharsets.UTF_8));

    final var push = this.putManifest(repo, reference, manifest, token);
    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);

    final var head = this.manifestRequest(true, repo, reference, token);
    final var get = this.manifestRequest(false, repo, reference, token);

    assertThat(head.getStatus()).as(head.getContentAsString()).isEqualTo(200);
    assertThat(get.getStatus()).as(get.getContentAsString()).isEqualTo(200);
    assertThat(get.getContentAsString()).isEqualTo(manifest);
    assertThat(get.getContentType()).isEqualTo(OCI_MANIFEST);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("a manifest pushed under a digest reference that is not its digest is refused")
  void manifestUnderWrongDigestIsRefused(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = this.pushManifestBlobs(repo, token);

    final var wrong = "sha512".equals(algorithm) ? "0".repeat(128) : "0".repeat(64);
    final var push = this.putManifest(repo, algorithm + ":" + wrong, manifest, token);

    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(400);
  }
}
