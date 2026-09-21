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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetBuildMetadataVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The failure paths that the integration test cannot reach without breaking the storage. */
@DisplayName("NuGetBuildMetadataVersionMigrationService failure handling (RPS-1059)")
class NuGetBuildMetadataVersionMigrationServiceTest {

  private final NuGetPackageVersionRepository versions = mock(NuGetPackageVersionRepository.class);
  private final VulnerabilityScanRepository scans = mock(VulnerabilityScanRepository.class);
  private final NuGetStorageService storage = mock(NuGetStorageService.class);

  private final UUID repoId = UUID.randomUUID();
  private final NuGetBuildMetadataVersion legacy =
      new NuGetBuildMetadataVersion(
          UUID.randomUUID(), UUID.randomUUID(), this.repoId, "repo", "Some.Package", "1.0.0+build");

  private NuGetBuildMetadataVersionMigrationService service;

  @BeforeEach
  void setUp() {
    this.service =
        new NuGetBuildMetadataVersionMigrationService(
            this.versions, this.scans, this.storage, mock(PlatformTransactionManager.class));
    when(this.versions.findAllWithBuildMetadata()).thenReturn(List.of(this.legacy));
  }

  private NuGetPackageVersion row() {
    final var row = new NuGetPackageVersion();
    row.setVersion("1.0.0+build");
    return row;
  }

  @Test
  @DisplayName("counts a version whose files cannot be copied as failed and leaves its row alone")
  void failedCopyLeavesRow() throws IOException {
    when(this.storage.copyToCanonicalVersion(any(), anyString(), anyString()))
        .thenThrow(new IOException("disk full"));

    final var report = this.service.migrate();

    assertThat(report.failed()).isEqualTo(1);
    assertThat(report.migrated()).isZero();
    assertThat(report.isEmpty()).isFalse();
    verify(this.versions, never()).saveAndFlush(any());
    verify(this.storage, never()).deletePackageVersion(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("counts a version whose row cannot be renamed as failed and keeps its files")
  void failedRenameKeepsFiles() throws IOException {
    when(this.storage.copyToCanonicalVersion(any(), anyString(), anyString())).thenReturn(true);
    when(this.versions.findById(this.legacy.id())).thenReturn(Optional.of(this.row()));
    doThrow(new IllegalStateException("unique violation")).when(this.versions).saveAndFlush(any());

    final var report = this.service.migrate();

    assertThat(report.failed()).isEqualTo(1);
    assertThat(report.migrated()).isZero();
    verify(this.storage, never()).deletePackageVersion(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("still counts a version as migrated when the old files cannot be removed")
  void failedCleanupStillMigrated() throws IOException {
    when(this.storage.copyToCanonicalVersion(any(), anyString(), anyString())).thenReturn(true);
    when(this.versions.findById(this.legacy.id())).thenReturn(Optional.of(this.row()));
    doThrow(new IOException("read-only"))
        .when(this.storage)
        .deletePackageVersion(eq(this.repoId), eq("some.package"), eq("1.0.0+build"));

    final var report = this.service.migrate();

    assertThat(report.migrated()).isEqualTo(1);
    assertThat(report.failed()).isZero();
    verify(this.scans).updateArtifactVersion(this.repoId, "some.package", "1.0.0+build", "1.0.0");
  }

  @Test
  @DisplayName("migrates the row of a version without files and has no files to remove")
  void rowWithoutFiles() throws IOException {
    when(this.storage.copyToCanonicalVersion(any(), anyString(), anyString())).thenReturn(false);
    when(this.versions.findById(this.legacy.id())).thenReturn(Optional.of(this.row()));

    final var report = this.service.migrate();

    assertThat(report.migrated()).isEqualTo(1);
    verify(this.storage, never()).deletePackageVersion(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("does not touch a version whose canonical version already exists")
  void conflictIsLeftAlone() throws IOException {
    when(this.versions.existsByNugetPackageIdAndVersionIgnoreCase(
            this.legacy.packageRowId(), "1.0.0"))
        .thenReturn(true);

    final var report = this.service.migrate();

    assertThat(report.conflicts()).containsExactly(this.legacy);
    assertThat(report.migrated()).isZero();
    verify(this.storage, never()).copyToCanonicalVersion(any(), anyString(), anyString());
    verify(this.versions, never()).saveAndFlush(any());
  }
}
