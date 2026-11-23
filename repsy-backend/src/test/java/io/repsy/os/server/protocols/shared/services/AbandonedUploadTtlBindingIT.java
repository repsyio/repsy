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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-2739: the env-style {@code ABANDONED_UPLOAD_*} variables the e2e {@code --upload-ttl} overlay
 * sets reach the cleanup, with no sleep. {@code AbandonedBlobUploadCleanupIT} covers the sweep with
 * the default 24 hour TTL; this class proves the variables are bound: the TTL into the service (an
 * upload idle for 10 seconds is swept, a fresh one is not) and the initial delay and interval into
 * the scheduled task.
 *
 * <p>The delays are one hour here, not the overlay's 1 and 2 seconds: the context stays alive for
 * the rest of the run, and a pass every 2 seconds on a 5 second TTL would sweep the uploads of the
 * classes that run after this one.
 */
@TestPropertySource(
    properties = {
      "ABANDONED_UPLOAD_TTL=PT5S",
      "ABANDONED_UPLOAD_CLEANUP_INITIAL_DELAY=PT61M",
      "ABANDONED_UPLOAD_CLEANUP_INTERVAL=PT62M",
      "ABANDONED_UPLOAD_CLEANUP_ENABLED=true"
    })
@DisplayName("Abandoned upload cleanup, env-style TTL binding (RPS-2739)")
class AbandonedUploadTtlBindingIT extends AbstractIT {

  private static final String IMAGE = "app";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private AbandonedBlobUploadCleanupService cleanupService;
  @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;
  @Autowired private Environment environment;

  private String startAndChunk(final Repo repo, final String token) throws Exception {
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

    final var chunk =
        this.mockMvc
            .perform(
                patch("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content("abandoned".getBytes(StandardCharsets.UTF_8))
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(chunk.getStatus()).isEqualTo(202);

    return uploadId;
  }

  private int uploadStatus(final Repo repo, final String uploadId, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            head("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("the variables are bound as ISO-8601 durations")
  void variablesAreBound() {
    final var prefix = "repsy.storage.abandoned-upload-cleanup.";

    assertThat(this.environment.getProperty(prefix + "ttl", Duration.class))
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(this.environment.getProperty(prefix + "initial-delay", Duration.class))
        .isEqualTo(Duration.ofMinutes(61));
    assertThat(this.environment.getProperty(prefix + "interval", Duration.class))
        .isEqualTo(Duration.ofMinutes(62));
    assertThat(this.environment.getProperty(prefix + "enabled", Boolean.class)).isTrue();
  }

  @Test
  @DisplayName("the scheduled task runs with the bound initial delay and interval")
  void scheduledTaskUsesTheBoundDurations() {
    final var tasks =
        this.scheduledTasks.getScheduledTasks().stream()
            .filter(task -> task.getTask().toString().contains("AbandonedBlobUploadCleanupTask"))
            .map(task -> task.getTask())
            .toList();

    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0)).isInstanceOf(FixedDelayTask.class);
    final var fixedDelay = (FixedDelayTask) tasks.get(0);
    assertThat(fixedDelay.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(61));
    assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofMinutes(62));
  }

  @Test
  @DisplayName("an upload idle for longer than the 5 second TTL is swept, a fresh one is kept")
  void sweepUsesTheBoundTtl() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("ttl"));
    final var idle = this.startAndChunk(repo, token);
    final var fresh = this.startAndChunk(repo, token);
    assertThat(this.uploadStatus(repo, idle, token)).isEqualTo(204);

    makeIdleFor(repo, idle, Duration.ofSeconds(10));
    this.cleanupService.cleanupAbandonedUploads();

    assertThat(this.uploadStatus(repo, idle, token)).isIn(404, 410);
    assertThat(this.uploadStatus(repo, fresh, token)).isEqualTo(204);
  }

  private static void makeIdleFor(final Repo repo, final String uploadId, final Duration idle)
      throws IOException {
    Files.setLastModifiedTime(
        storageDirOf(repo).resolve("blobs").resolve(uploadId),
        FileTime.from(Instant.now().minus(idle)));
  }
}
