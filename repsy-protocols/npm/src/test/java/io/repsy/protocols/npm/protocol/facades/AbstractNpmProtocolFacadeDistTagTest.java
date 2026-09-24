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
package io.repsy.protocols.npm.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.util.Pair;

/**
 * RPS-1272: {@link AbstractNpmProtocolFacade#addDistributionTag} and {@link
 * AbstractNpmProtocolFacade#removeDistributionTag} hand the package metadata write to {@link
 * NpmPackageService}, which writes the tag row first, and the metadata change to {@link
 * AbstractNpmStorageService#changeMetadata}, which puts the metadata back when that write fails.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmProtocolFacade dist-tags (RPS-1272)")
class AbstractNpmProtocolFacadeDistTagTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final String PACKAGE = "demo";
  private static final String TAG = "next";
  private static final String VERSION = "1.2.3";
  private static final Path BASE_PATH = Path.of(PACKAGE);
  private static final NpmPackageSnapshot SNAPSHOT =
      new NpmPackageSnapshot(null, PACKAGE, "1.2.3", Instant.EPOCH, List.of(), Map.of());

  @Mock private NpmPackageService<UUID> packageService;
  @Mock private AbstractNpmStorageService storageService;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractNpmProtocolFacade<UUID> {

    TestFacade(
        final NpmPackageService<UUID> packageService, final AbstractNpmStorageService storage) {
      super(packageService, storage);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade = new TestFacade(this.packageService, this.storageService);

    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/"))
            .repoInfo(repoInfo)
            .build());
  }

  private void basePath() {
    when(this.storageService.getPackageBasePath(null, PACKAGE)).thenReturn(BASE_PATH);
  }

  /** Makes the mocked service run the metadata writer like the real one does after the rows. */
  private void addRuns() throws IOException {
    when(this.packageService.addDistributionTag(
            any(), any(), eq(PACKAGE), eq(TAG), eq(VERSION), any()))
        .thenAnswer(
            invocation -> invocation.<NpmPackageService.MetadataWriter>getArgument(5).write());
  }

  private void removeRuns() throws IOException {
    when(this.packageService.removeDistributionTag(any(), any(), eq(PACKAGE), eq(TAG), any()))
        .thenAnswer(
            invocation -> invocation.<NpmPackageService.MetadataWriter>getArgument(4).write());
  }

  /**
   * Makes the mocked storage service run the change it is given, as the real one does whether or
   * not it had to rebuild the file first, and hand out the rows it is given.
   */
  private void metadataChangesRun() throws IOException {
    when(this.storageService.changeMetadata(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(invocation.<Supplier<NpmPackageSnapshot>>getArgument(3).get())
                  .as("the rows the storage service rebuilds a lost file from")
                  .isSameAs(SNAPSHOT);
              return invocation.<NpmStorageService.MetadataChange>getArgument(4).apply();
            });
    when(this.packageService.getSnapshot(REPO_ID, null, PACKAGE)).thenReturn(SNAPSHOT);
  }

  private void add() throws IOException {
    // npm sends the version as a JSON string, quotes included.
    this.facade.addDistributionTag(this.context, null, PACKAGE, TAG, "\"" + VERSION + "\"");
  }

  @Test
  @DisplayName("add writes the changed metadata inside the service call and reports its usage")
  void addWritesTheMetadata() throws Exception {
    this.basePath();
    this.addRuns();
    final var metadata = new LinkedHashMap<String, Object>();
    this.metadataChangesRun();
    when(this.storageService.addDistributionTag(REPO_ID, REPO_NAME, BASE_PATH, TAG, VERSION))
        .thenReturn(Pair.of(metadata, 7L));

    this.add();

    verify(this.storageService).writeMetadataToFile(eq(REPO_NAME), eq(metadata), any());
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(7L);
  }

  @Test
  @DisplayName("add reports nothing when the write fails")
  void addReportsNothingWhenTheWriteFails() throws Exception {
    final var failure = new IOException("disk full");
    this.basePath();
    this.addRuns();
    this.metadataChangesRun();
    when(this.storageService.addDistributionTag(any(), any(), any(), any(), any()))
        .thenReturn(Pair.of(new LinkedHashMap<>(), 7L));
    doThrow(failure).when(this.storageService).writeMetadataToFile(any(), any(), any());

    assertThatThrownBy(this::add).isSameAs(failure);

    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("add does not touch storage when the service rejects the tag")
  void addWritesNothingWhenTheRowsAreRejected() throws Exception {
    this.basePath();
    when(this.packageService.addDistributionTag(any(), any(), any(), any(), any(), any()))
        .thenThrow(new BadRequestException("packageVersionNotFound"));

    assertThatThrownBy(this::add).isInstanceOf(BadRequestException.class);

    verify(this.storageService, never()).changeMetadata(any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("remove rewrites the metadata inside the service call and reports its usage")
  void removeWritesTheMetadata() throws Exception {
    this.basePath();
    this.removeRuns();
    this.metadataChangesRun();
    when(this.storageService.removeDistributionTag(REPO_ID, REPO_NAME, BASE_PATH, TAG))
        .thenReturn(-9L);

    this.facade.removeDistributionTag(this.context, null, PACKAGE, TAG);

    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(-9L);
  }

  @Test
  @DisplayName("remove reports nothing when the write fails")
  void removeReportsNothingWhenTheWriteFails() throws Exception {
    final var failure = new IllegalStateException("storage went away");
    this.basePath();
    this.removeRuns();
    this.metadataChangesRun();
    when(this.storageService.removeDistributionTag(any(), any(), any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> this.facade.removeDistributionTag(this.context, null, PACKAGE, TAG))
        .isSameAs(failure);

    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("the latest tag cannot be removed and nothing is locked or written")
  void latestCannotBeRemoved() throws Exception {
    assertThatThrownBy(
            () -> this.facade.removeDistributionTag(this.context, null, PACKAGE, "latest"))
        .isInstanceOf(BadRequestException.class);

    verify(this.packageService, never()).removeDistributionTag(any(), any(), any(), any(), any());
  }
}
