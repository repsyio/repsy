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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService.PackageDeletion;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService.PackageRemover;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService.VersionRemover;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
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
 * RPS-1280 and RPS-1289: unpublishing, deprecating and deleting through {@link
 * AbstractNpmProtocolFacade} hand the file changes to {@link NpmPackageService}, which writes the
 * rows first, and report the usages the files reported.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmProtocolFacade unpublish, deprecate and delete (RPS-1280)")
class AbstractNpmProtocolFacadeDeleteTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PACKAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String REPO_NAME = "npm-repo";
  private static final String PACKAGE = "demo";
  private static final Path BASE_PATH = Path.of(PACKAGE);
  private static final NpmPackageSnapshot SNAPSHOT =
      new NpmPackageSnapshot(null, PACKAGE, "1.0.0", Instant.EPOCH, List.of(), Map.of());

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

  private static Map<String, Object> metadataWithVersions(final String... versions) {
    final var all = new HashMap<String, Object>();

    for (final var version : versions) {
      all.put(version, new HashMap<String, Object>());
    }

    final var metadata = new HashMap<String, Object>();
    metadata.put("versions", all);

    return metadata;
  }

  private void basePath() {
    when(this.storageService.getPackageBasePath(null, PACKAGE)).thenReturn(BASE_PATH);
  }

  @Test
  @DisplayName("unpublishing a version deletes it through the service and reports the freed bytes")
  void unpublishRemovesTheVersionFilesInsideTheService() throws Exception {
    this.basePath();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0", "1.1.0"));
    when(this.storageService.removeVersion(
            eq(REPO_ID),
            eq(REPO_NAME),
            eq(BASE_PATH),
            eq(PACKAGE),
            eq("1.1.0"),
            eq("1.0.0"),
            any()))
        .thenReturn(-150L);
    when(this.packageService.deletePackageVersion(
            any(), any(), eq(PACKAGE), eq("1.1.0"), any(), any()))
        .thenAnswer(
            invocation ->
                new PackageDeletion(
                    List.of("1.1.0"),
                    invocation.<VersionRemover>getArgument(4).removeVersion("1.0.0")));

    final var unpublished =
        this.facade.unPublishPackageVersion(
            this.context, null, PACKAGE, metadataWithVersions("1.0.0"));

    assertThat(unpublished).isEqualTo("1.1.0");
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(-150L);
  }

  @Test
  @DisplayName("unpublishing the last version removes the package files instead")
  void unpublishOfTheLastVersionRemovesThePackage() throws Exception {
    this.basePath();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0"));
    when(this.storageService.deletePackage(REPO_ID, BASE_PATH)).thenReturn(300L);
    when(this.packageService.deletePackageVersion(
            any(), any(), eq(PACKAGE), eq("1.0.0"), any(), any()))
        .thenAnswer(
            invocation ->
                new PackageDeletion(
                    List.of("1.0.0"), invocation.<PackageRemover>getArgument(5).removePackage()));

    this.facade.unPublishPackageVersion(this.context, null, PACKAGE, metadataWithVersions());

    verify(this.storageService, never())
        .removeVersion(any(), any(), any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(-300L);
  }

  @Test
  @DisplayName("a payload that lacks no version is a conflict and unpublishes nothing")
  void unpublishOfNothingIsAConflict() throws Exception {
    this.basePath();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0"));

    assertThatThrownBy(
            () ->
                this.facade.unPublishPackageVersion(
                    this.context, null, PACKAGE, metadataWithVersions("1.0.0")))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("unpublishPayloadStale");

    verify(this.packageService, never())
        .deletePackageVersion(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a payload that lacks a version published after it was read is a conflict")
  void unpublishOfAStalePayloadIsAConflict() throws Exception {
    this.basePath();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0", "2.0.0"));

    assertThatThrownBy(
            () ->
                this.facade.unPublishPackageVersion(
                    this.context, null, PACKAGE, metadataWithVersions()))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("unpublishPayloadStale");

    verify(this.packageService, never())
        .deletePackageVersion(any(), any(), any(), any(), any(), any());
  }

  private BasePackageInfo<UUID> packageInfo() {
    return BasePackageInfo.<UUID>builder().id(PACKAGE_ID).build();
  }

  @Test
  @DisplayName("the tarball request after an unpublish finds the version gone and deletes nothing")
  void tarballDeleteOfAnUnpublishedVersionIsANoOp() throws Exception {
    when(this.packageService.getPackage(REPO_ID, null, PACKAGE)).thenReturn(this.packageInfo());
    when(this.packageService.getVersionNames(PACKAGE_ID)).thenReturn(List.of("1.1.0"));

    this.facade.deletePackageTarball(this.context, null, PACKAGE, "demo-1.0.0.tgz");

    verify(this.packageService, never())
        .deletePackageVersion(any(), any(), any(), any(), any(), any());
    verify(this.packageService, never()).deletePackage(any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("the tarball request of a package that is gone is a no-op too")
  void tarballDeleteOfAMissingPackageIsANoOp() {
    when(this.packageService.getPackage(REPO_ID, null, PACKAGE))
        .thenThrow(new ItemNotFoundException("packageNotFound"));

    this.facade.deletePackageTarball(this.context, null, PACKAGE, "demo-1.0.0.tgz");

    verify(this.packageService, never()).deletePackage(any(), any(), any(), any());
  }

  @Test
  @DisplayName("the tarball request of a version that is still published is a conflict")
  void tarballDeleteOfAPublishedVersionIsAConflict() throws Exception {
    when(this.packageService.getPackage(REPO_ID, null, PACKAGE)).thenReturn(this.packageInfo());
    when(this.packageService.getVersionNames(PACKAGE_ID)).thenReturn(List.of("1.0.0"));

    assertThatThrownBy(
            () -> this.facade.deletePackageTarball(this.context, null, PACKAGE, "demo-1.0.0.tgz"))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("npmVersionStillPublished");

    verify(this.packageService, never())
        .deletePackageVersion(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a tarball file name that is not the package's is a bad request")
  void tarballDeleteOfAnotherFileIsABadRequest() {
    assertThatThrownBy(
            () -> this.facade.deletePackageTarball(this.context, null, PACKAGE, "other-1.0.0.tgz"))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(this.packageService);
  }

  @Test
  @DisplayName("deleting a package removes its files inside the service and reports the bytes")
  void deletePackageRemovesTheFilesInsideTheService() {
    this.basePath();
    when(this.storageService.deletePackage(REPO_ID, BASE_PATH)).thenReturn(42L);
    when(this.packageService.deletePackage(any(), any(), eq(PACKAGE), any()))
        .thenAnswer(
            invocation ->
                new PackageDeletion(
                    List.of("1.0.0"), invocation.<PackageRemover>getArgument(3).removePackage()));

    this.facade.deletePackage(this.context, null, PACKAGE);

    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(-42L);
  }

  @Test
  @DisplayName("a package delete the service rejects removes no file and reports nothing")
  void deletePackageWritesNothingWhenTheRowsAreRejected() {
    this.basePath();
    when(this.packageService.deletePackage(any(), any(), any(), any()))
        .thenThrow(new ItemNotFoundException("packageNotFound"));

    assertThatThrownBy(() -> this.facade.deletePackage(this.context, null, PACKAGE))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.storageService, never()).deletePackage(any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  private Map<String, Object> deprecatedPayload() {
    final var metadata = metadataWithVersions("1.0.0");
    @SuppressWarnings("unchecked")
    final var version =
        (Map<String, Object>) ((Map<String, Object>) metadata.get("versions")).get("1.0.0");
    version.put("deprecated", "use 1.1");

    return metadata;
  }

  /** The storage service runs the change it is given, like the real one when the file is there. */
  private void metadataChangesRun() throws IOException {
    when(this.storageService.changeMetadata(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any(), any()))
        .thenAnswer(
            invocation -> invocation.<NpmStorageService.MetadataChange>getArgument(4).apply());
  }

  private void deprecationRuns() throws IOException {
    when(this.packageService.handleDeprecations(any(), any(), eq(PACKAGE), any(), any()))
        .thenAnswer(
            invocation -> invocation.<NpmPackageService.MetadataWriter>getArgument(4).write());
  }

  @Test
  @DisplayName("deprecating changes the stored metadata inside the service and reports its growth")
  void deprecateChangesTheMetadataInsideTheService() throws Exception {
    this.basePath();
    this.deprecationRuns();
    this.metadataChangesRun();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0"));
    when(this.storageService.deprecateVersions(eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(17L);

    this.facade.deprecate(this.context, null, PACKAGE, this.deprecatedPayload());

    verify(this.storageService)
        .deprecateVersions(REPO_ID, REPO_NAME, BASE_PATH, List.of(Pair.of("1.0.0", "use 1.1")));
    verify(this.storageService, never()).restoreMetadataBytes(any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(17L);
  }

  @Test
  @DisplayName("a deprecation whose metadata write fails reports nothing")
  void deprecateReportsNothingWhenTheWriteFails() throws Exception {
    final var failure = new IOException("disk full");
    this.basePath();
    this.deprecationRuns();
    this.metadataChangesRun();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0"));
    doThrow(failure).when(this.storageService).deprecateVersions(any(), any(), any(), any());

    assertThatThrownBy(
            () -> this.facade.deprecate(this.context, null, PACKAGE, this.deprecatedPayload()))
        .isSameAs(failure);

    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("the rows are asked for only through the supplier the storage service is handed")
  void deprecateGivesTheStorageServiceTheRows() throws Exception {
    this.basePath();
    this.deprecationRuns();
    when(this.storageService.readMetadataOrRebuild(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any()))
        .thenReturn(metadataWithVersions("1.0.0"));
    when(this.storageService.changeMetadata(
            eq(REPO_ID), eq(REPO_NAME), eq(BASE_PATH), any(), any()))
        .thenAnswer(
            invocation -> {
              final var rows = invocation.<Supplier<NpmPackageSnapshot>>getArgument(3).get();
              assertThat(rows).isSameAs(SNAPSHOT);
              return 0L;
            });
    when(this.packageService.getSnapshot(REPO_ID, null, PACKAGE)).thenReturn(SNAPSHOT);

    this.facade.deprecate(this.context, null, PACKAGE, this.deprecatedPayload());

    verify(this.packageService).getSnapshot(REPO_ID, null, PACKAGE);
  }
}
