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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbandonedBlobUploadCleanupService")
class AbandonedBlobUploadCleanupServiceTest {

  private static final Duration TTL = Duration.ofHours(24);
  private static final Instant THRESHOLD = Instant.parse("2026-06-01T00:00:00Z");
  private static final String UPLOAD_A = "0193c1d6-7a3e-7cc1-8f00-0123456789ab";
  private static final String UPLOAD_B = "0193c1d6-7a3e-7cc1-8f00-ba9876543210";
  private static final String DIGEST =
      "sha256:0000000000000000000000000000000000000000000000000000000000000001";

  @Mock RepoRepository repoRepository;
  @Mock LayerRepository layerRepository;
  @Mock DockerStorageService dockerStorageService;
  @Mock HelmStorageService helmStorageService;
  @Mock UsageUpdateService usageUpdateService;

  AbandonedBlobUploadCleanupService service;

  @BeforeEach
  void setUp() {
    this.service =
        new AbandonedBlobUploadCleanupService(
            this.repoRepository,
            this.layerRepository,
            this.dockerStorageService,
            this.helmStorageService,
            this.usageUpdateService,
            TTL);
  }

  private static Repo repo(final RepoType type) {
    final var repo = new Repo();
    repo.setId(UUID.randomUUID());
    repo.setName(type.name().toLowerCase() + "-repo");
    repo.setType(type);
    return repo;
  }

  private void givenRepos(final Repo docker, final Repo helm) {
    when(this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(RepoType.DOCKER))
        .thenReturn(docker == null ? List.of() : List.of(docker));
    when(this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(RepoType.HELM))
        .thenReturn(helm == null ? List.of() : List.of(helm));
  }

  private long releasedBy(final UUID repoId) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService).updateUsage(captor.capture());
    assertThat(captor.getValue().repoId()).isEqualTo(repoId);
    return -captor.getValue().usages().getDiskUsage();
  }

  @Test
  @DisplayName("deletes a stale Docker upload and releases its bytes from the repo")
  void deletesStaleDockerUploadAndReleasesUsage() throws IOException {
    final var docker = repo(RepoType.DOCKER);
    this.givenRepos(docker, null);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(UPLOAD_A, 100L)));
    when(this.layerRepository.existsByIdAndRepoId(UUID.fromString(UPLOAD_A), docker.getId()))
        .thenReturn(false);
    when(this.dockerStorageService.deleteBlobFile(docker.getId(), docker.getName(), UPLOAD_A))
        .thenReturn(100L);

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isEqualTo(100L);
    assertThat(this.releasedBy(docker.getId())).isEqualTo(100L);
  }

  @Test
  @DisplayName("deletes a stale Helm OCI upload and releases its bytes from the repo")
  void deletesStaleHelmUploadAndReleasesUsage() throws IOException {
    final var helm = repo(RepoType.HELM);
    this.givenRepos(null, helm);
    when(this.helmStorageService.listStaleBlobFiles(helm.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(UPLOAD_A, 40L), new StaleFile(UPLOAD_B, 2L)));
    when(this.helmStorageService.deleteBlobFile(helm.getId(), helm.getName(), UPLOAD_A))
        .thenReturn(40L);
    when(this.helmStorageService.deleteBlobFile(helm.getId(), helm.getName(), UPLOAD_B))
        .thenReturn(2L);

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isEqualTo(42L);
    assertThat(this.releasedBy(helm.getId())).isEqualTo(42L);
    verifyNoInteractions(this.layerRepository);
  }

  @Test
  @DisplayName("leaves a finalized blob, which is named by its digest")
  void leavesFinalizedBlob() throws IOException {
    final var docker = repo(RepoType.DOCKER);
    final var helm = repo(RepoType.HELM);
    this.givenRepos(docker, helm);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(DIGEST, 500L)));
    when(this.helmStorageService.listStaleBlobFiles(helm.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(DIGEST, 500L)));

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isZero();
    verify(this.dockerStorageService, never()).deleteBlobFile(any(), anyString(), anyString());
    verify(this.helmStorageService, never()).deleteBlobFile(any(), anyString(), anyString());
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("leaves a Docker layer stored under the id of its layer row")
  void leavesDockerLayerStoredUnderItsRowId() throws IOException {
    final var docker = repo(RepoType.DOCKER);
    this.givenRepos(docker, null);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(UPLOAD_A, 100L)));
    when(this.layerRepository.existsByIdAndRepoId(UUID.fromString(UPLOAD_A), docker.getId()))
        .thenReturn(true);

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isZero();
    verify(this.dockerStorageService, never()).deleteBlobFile(any(), anyString(), anyString());
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("releases nothing for a repo without abandoned uploads")
  void releasesNothingWhenThereAreNoStaleFiles() {
    final var docker = repo(RepoType.DOCKER);
    this.givenRepos(docker, null);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenReturn(List.of());

    assertThat(this.service.cleanupAbandonedUploads(THRESHOLD)).isZero();

    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("skips a file that fails to delete and releases only what was deleted")
  void skipsFileThatFailsToDelete() throws IOException {
    final var docker = repo(RepoType.DOCKER);
    this.givenRepos(docker, null);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(UPLOAD_A, 100L), new StaleFile(UPLOAD_B, 7L)));
    when(this.dockerStorageService.deleteBlobFile(docker.getId(), docker.getName(), UPLOAD_A))
        .thenThrow(new IOException("disk error"));
    when(this.dockerStorageService.deleteBlobFile(docker.getId(), docker.getName(), UPLOAD_B))
        .thenReturn(7L);

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isEqualTo(7L);
    assertThat(this.releasedBy(docker.getId())).isEqualTo(7L);
  }

  @Test
  @DisplayName("keeps cleaning the other repos when one repo fails")
  void keepsCleaningWhenOneRepoFails() throws IOException {
    final var docker = repo(RepoType.DOCKER);
    final var helm = repo(RepoType.HELM);
    this.givenRepos(docker, helm);
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenThrow(new IllegalStateException("storage unavailable"));
    when(this.helmStorageService.listStaleBlobFiles(helm.getId(), THRESHOLD))
        .thenReturn(List.of(new StaleFile(UPLOAD_A, 9L)));
    when(this.helmStorageService.deleteBlobFile(helm.getId(), helm.getName(), UPLOAD_A))
        .thenReturn(9L);

    final var released = this.service.cleanupAbandonedUploads(THRESHOLD);

    assertThat(released).isEqualTo(9L);
    assertThat(this.releasedBy(helm.getId())).isEqualTo(9L);
  }

  @Test
  @DisplayName("measures the TTL back from now when no threshold is given")
  void usesTheConfiguredTtl() {
    final var docker = repo(RepoType.DOCKER);
    this.givenRepos(docker, null);
    final var before = Instant.now().minus(TTL);
    when(this.dockerStorageService.listStaleBlobFiles(any(), any())).thenReturn(List.of());

    this.service.cleanupAbandonedUploads();

    final var captor = ArgumentCaptor.forClass(Instant.class);
    verify(this.dockerStorageService).listStaleBlobFiles(any(), captor.capture());
    assertThat(captor.getValue()).isBetween(before, Instant.now().minus(TTL));
  }

  @Test
  @DisplayName("does not start a second pass while one is running")
  void doesNotRunTwoPassesAtOnce() throws Exception {
    final var docker = repo(RepoType.DOCKER);
    final var listing = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    when(this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(RepoType.DOCKER))
        .thenReturn(List.of(docker));
    when(this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(RepoType.HELM))
        .thenReturn(List.of());
    when(this.dockerStorageService.listStaleBlobFiles(docker.getId(), THRESHOLD))
        .thenAnswer(
            invocation -> {
              listing.countDown();
              release.await(10, TimeUnit.SECONDS);
              return List.of();
            });

    final var executor = Executors.newSingleThreadExecutor();
    try {
      final var firstPass = executor.submit(() -> this.service.cleanupAbandonedUploads(THRESHOLD));
      assertThat(listing.await(10, TimeUnit.SECONDS)).isTrue();

      assertThat(this.service.cleanupAbandonedUploads(THRESHOLD)).isZero();

      release.countDown();
      assertThat(firstPass.get(10, TimeUnit.SECONDS)).isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    verify(this.dockerStorageService).listStaleBlobFiles(docker.getId(), THRESHOLD);
  }
}
