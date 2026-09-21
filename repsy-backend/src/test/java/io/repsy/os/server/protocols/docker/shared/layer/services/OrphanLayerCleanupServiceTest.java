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
package io.repsy.os.server.protocols.docker.shared.layer.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrphanLayerCleanupService")
class OrphanLayerCleanupServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final OrphanLayerInfo FIRST = new OrphanLayerInfo("sha256:first", 100L);
  private static final OrphanLayerInfo SECOND = new OrphanLayerInfo("sha256:second", 20L);
  private static final OrphanLayerInfo THIRD = new OrphanLayerInfo("sha256:third", 3L);

  @Mock DockerStorageService dockerStorageService;
  @Mock UsageUpdateService usageUpdateService;

  OrphanLayerCleanupService service;

  @BeforeEach
  void setUp() {
    this.service =
        new OrphanLayerCleanupService(this.dockerStorageService, this.usageUpdateService);
  }

  @AfterEach
  void clearInterruptFlag() {
    // Thread.interrupted() reads and clears the flag so it cannot leak into the next test.
    Thread.interrupted();
  }

  private long releasedBytes() {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService).updateUsage(captor.capture());
    assertThat(captor.getValue().repoId()).isEqualTo(REPO_ID);
    return -captor.getValue().usages().getDiskUsage();
  }

  /**
   * Makes deleting the blob of {@code orphan} fail. The stub is lenient because strict stubs would
   * reject the calls for the other blobs as an argument mismatch.
   */
  private void failToDelete(final OrphanLayerInfo orphan, final Answer<?> answer) {
    lenient().doAnswer(answer).when(this.dockerStorageService).deleteBlob(REPO_ID, orphan.digest());
  }

  private void failToDelete(final OrphanLayerInfo orphan) {
    this.failToDelete(
        orphan,
        invocation -> {
          throw new IllegalStateException("storage unavailable");
        });
  }

  private void verifyBlobDeleted(final OrphanLayerInfo orphan) {
    verify(this.dockerStorageService).deleteBlob(REPO_ID, orphan.digest());
  }

  @Test
  @DisplayName("deletes every blob and releases the sum of their sizes with one usage update")
  void deletesAllBlobsAndReleasesTheirSize() {
    this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND, THIRD));

    this.verifyBlobDeleted(FIRST);
    this.verifyBlobDeleted(SECOND);
    this.verifyBlobDeleted(THIRD);
    assertThat(this.releasedBytes()).isEqualTo(123L);
  }

  @Test
  @DisplayName("does nothing for an empty list")
  void doesNothingForEmptyList() {
    this.service.cleanupBlobs(REPO_ID, List.of());

    verifyNoInteractions(this.dockerStorageService, this.usageUpdateService);
  }

  @Test
  @DisplayName("keeps deleting after a blob fails and releases only the bytes actually freed")
  void keepsDeletingAfterOneBlobFails() {
    this.failToDelete(SECOND);

    assertThatCode(() -> this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND, THIRD)))
        .doesNotThrowAnyException();

    this.verifyBlobDeleted(FIRST);
    this.verifyBlobDeleted(SECOND);
    this.verifyBlobDeleted(THIRD);
    assertThat(this.releasedBytes()).isEqualTo(FIRST.size() + THIRD.size());
  }

  @Test
  @DisplayName("still releases what was freed when the first blob fails")
  void releasesRemainingWhenFirstBlobFails() {
    this.failToDelete(FIRST);

    this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND, THIRD));

    assertThat(this.releasedBytes()).isEqualTo(SECOND.size() + THIRD.size());
  }

  @Test
  @DisplayName("sends no usage update when every blob fails")
  void sendsNoUsageUpdateWhenEveryBlobFails() {
    this.failToDelete(FIRST);
    this.failToDelete(SECOND);

    assertThatCode(() -> this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND)))
        .doesNotThrowAnyException();

    this.verifyBlobDeleted(FIRST);
    this.verifyBlobDeleted(SECOND);
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("survives a checked IOException that the storage layer throws sneakily")
  void survivesSneakilyThrownCheckedException() {
    // deleteBlob declares no checked exception, but FileSystemStorageStrategy rethrows IOException
    // (NoSuchFileException for a missing blob) sneakily, so a catch of RuntimeException is not
    // enough.
    this.failToDelete(
        FIRST,
        invocation -> {
          throw new IOException("no such file");
        });

    assertThatCode(() -> this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND)))
        .doesNotThrowAnyException();

    this.verifyBlobDeleted(SECOND);
    assertThat(this.releasedBytes()).isEqualTo(SECOND.size());
  }

  @Test
  @DisplayName("stops on an interrupt, restores the interrupt flag and releases what was freed")
  void stopsOnInterrupt() {
    this.failToDelete(
        SECOND,
        invocation -> {
          throw new InterruptedException("shutting down");
        });

    this.service.cleanupBlobs(REPO_ID, List.of(FIRST, SECOND, THIRD));

    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verify(this.dockerStorageService, never()).deleteBlob(REPO_ID, THIRD.digest());
    assertThat(this.releasedBytes()).isEqualTo(FIRST.size());
  }
}
