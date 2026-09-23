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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
