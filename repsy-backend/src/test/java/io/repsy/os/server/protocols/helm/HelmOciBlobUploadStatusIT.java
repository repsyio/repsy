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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Helm OCI blob upload status route (RPS-2161): it is a write operation like its sibling upload
 * routes, so a public repo still demands credentials for it, and polling it is not a push, so it
 * publishes no {@link ArtifactPushedEvent}.
 */
@RecordApplicationEvents
@DisplayName("Helm OCI blob upload status")
class HelmOciBlobUploadStatusIT extends AbstractIT {

  private static final String CHART = "app";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ApplicationEvents applicationEvents;

  private Repo helmRepo(final boolean scanEnabled) {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));

    if (!scanEnabled) {
      return repo;
    }

    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setSecurityScanEnabled(true);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
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
    assertThat(start.getStatus()).as("blob upload start").isEqualTo(202);

    final var location = start.getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void patchChunk(final Repo repo, final String uploadId, final String token)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content("chunk".getBytes(StandardCharsets.UTF_8))
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("blob chunk upload").isEqualTo(202);
  }

  private int status(final MockHttpServletRequestBuilder request, final String token)
      throws Exception {
    final var builder = token == null ? request : request.header(AUTHORIZATION, token);

    return this.mockMvc.perform(builder.with(protocolPort())).andReturn().getResponse().getStatus();
  }

  @Test
  @DisplayName("an anonymous GET or HEAD of an upload status on a public repo is refused")
  void anonymousStatusIsRefused() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo(false);
    final var uploadId = this.startUpload(repo, token);

    assertThat(
            this.status(
                get("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId), null))
        .isEqualTo(401);
    assertThat(
            this.status(
                head("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                null))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("a GET or HEAD of an upload status with credentials that do not verify is refused")
  void unverifiedCredentialsAreRefused() throws Exception {
    final var repo = this.helmRepo(false);
    final var uploadId = this.startUpload(repo, this.adminProtocolBearerToken());
    final var basic =
        "Basic "
            + Base64.getEncoder().encodeToString("nobody:wrong".getBytes(StandardCharsets.UTF_8));

    // HelmHeaderPreProcessor only checks that an Authorization header is present; without the
    // write flag HelmAuthPreProcessor then skipped verifying it on a public repo (RPS-2161).
    for (final var credentials : List.of(basic, "Bearer not-a-token")) {
      assertThat(
              this.status(
                  get("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                  credentials))
          .as("GET with %s", credentials)
          .isEqualTo(401);
      assertThat(
              this.status(
                  head("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                  credentials))
          .as("HEAD with %s", credentials)
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("an authenticated GET or HEAD of an upload status answers the upload progress")
  void authenticatedStatusIsAnswered() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo(false);
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, token);

    assertThat(
            this.status(
                get("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                token))
        .isEqualTo(204);
    assertThat(
            this.status(
                head("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                token))
        .isEqualTo(204);
  }

  @Test
  @DisplayName("an upload start, chunk and status poll publish no push event")
  void uploadRoutesPublishNoPushEvent() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.helmRepo(true);
    final var uploadId = this.startUpload(repo, token);
    this.patchChunk(repo, uploadId, token);

    assertThat(
            this.status(
                get("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                token))
        .isEqualTo(204);
    assertThat(
            this.status(
                head("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId),
                token))
        .isEqualTo(204);

    assertThat(this.applicationEvents.stream(ArtifactPushedEvent.class)).isEmpty();
  }
}
