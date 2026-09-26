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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/**
 * RPS-1241: the {@code POST .../blobs/uploads/} response names one upload session. Its {@code
 * Location} header (what the client PATCHes and PUTs against) and its {@code Docker-Upload-UUID}
 * header used to be minted by two separate {@code getUuid()} calls, so they carried different ids.
 */
@DisplayName("Docker upload start")
class DockerUploadStartIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Test
  @DisplayName(
      "Location and Docker-Upload-UUID carry the same session id, and a status GET on it works")
  void locationAndUploadUuidAgree() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var token = this.adminProtocolBearerToken();

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
    final var uploadUuid = start.getHeader("Docker-Upload-UUID");

    assertThat(location).isNotNull();
    assertThat(uploadUuid).isNotNull();
    assertThat(location.substring(location.lastIndexOf('/') + 1))
        .as("the Location path segment")
        .isEqualTo(uploadUuid);
    assertThat(UUID.fromString(uploadUuid)).isNotNull();

    // The registry keeps no state for a session until its first byte arrives, so the session is
    // "resumed" the way a client does: a chunk to the Location it was given, then a status GET on
    // the id the Docker-Upload-UUID header named.
    final var chunk = "first-chunk".getBytes(StandardCharsets.UTF_8);
    final var patch =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadUuid)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(chunk)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(patch.getStatus()).isEqualTo(202);
    assertThat(patch.getHeader("Docker-Upload-UUID")).isEqualTo(uploadUuid);

    final var status =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadUuid)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(status.getStatus()).isEqualTo(204);
    assertThat(status.getHeader("Docker-Upload-UUID")).isEqualTo(uploadUuid);
    assertThat(status.getHeader("Range")).isEqualTo("0-" + (chunk.length - 1));
  }

  /**
   * RPS-1594: {@code POST .../blobs/uploads/?digest-algorithm=sha512} (OCI end-4c) starts an upload
   * that a {@code sha512:} finalize completes and answers with that digest (serving a stored blob
   * by its sha512 is {@code DockerBlobPullIT}'s).
   */
  @Test
  @DisplayName("a digest-algorithm=sha512 start hint is honoured by a sha512 finalize")
  void sha512HintStartsAnUploadThatASha512DigestFinalizes() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
    final var token = this.adminProtocolBearerToken();
    final var content = ("sha512-blob-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    final var digest =
        "sha512:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));

    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .param("digest-algorithm", "sha512")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(start.getStatus()).isEqualTo(202);
    final var uploadUuid = start.getHeader("Docker-Upload-UUID");
    assertThat(uploadUuid).isNotNull();

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadUuid)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(content)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(finalize.getStatus()).isEqualTo(201);
    assertThat(finalize.getHeader("Docker-Content-Digest")).isEqualTo(digest);
  }

  @Test
  @DisplayName("a digest-algorithm=sha256 start hint is accepted like no hint")
  void sha256HintIsAccepted() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));

    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .param("digest-algorithm", "sha256")
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(start.getStatus()).isEqualTo(202);
    assertThat(start.getHeader("Docker-Upload-UUID")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha384", "SHA512", "bogus:sha512"})
  @DisplayName("a digest-algorithm the registry cannot check is a 400 DIGEST_INVALID")
  void unsupportedHintIsRefused(final String algorithm) throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));

    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .param("digest-algorithm", algorithm)
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(start.getStatus()).isEqualTo(400);
    assertThat(start.getContentAsString()).contains("DIGEST_INVALID");
    assertThat(start.getHeader("Docker-Upload-UUID")).isNull();
    assertThat(start.getHeader("Location")).isNull();
  }
}
