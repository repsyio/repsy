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
package io.repsy.protocols.nuget.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetStorageService version paths (RPS-996)")
class AbstractNuGetStorageServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final String CANONICAL_NUPKG =
      "packages/some.package/1.0.0/some.package.1.0.0.nupkg";
  private static final String CANONICAL_NUSPEC =
      "packages/some.package/1.0.0/some.package.1.0.0.nuspec";
  private static final String LEGACY_NUPKG =
      "packages/some.package/1.0.0+build/some.package.1.0.0+build.nupkg";

  @Mock private StorageStrategy storageStrategy;

  private NuGetStorageService service;

  @BeforeEach
  void setUp() {
    this.service = new AbstractNuGetStorageService(this.storageStrategy) {};
  }

  private static String relativePath(final StoragePath path) {
    return path.getPath().substring(REPO_ID.toString().length() + 1);
  }

  /** Makes only the file at {@code relativePath} exist in the storage. */
  private void stubOnlyExisting(final String relativePath, final Resource resource) {
    when(this.storageStrategy.get(any(StoragePath.class), eq(REPO_ID.toString())))
        .thenAnswer(
            invocation ->
                relativePath(invocation.getArgument(0)).equals(relativePath)
                    ? Optional.of(resource)
                    : Optional.empty());
  }

  @Test
  @DisplayName("writes a package with build metadata under its canonical version")
  void writesUnderCanonicalVersion() throws IOException {
    when(this.storageStrategy.write(anyString(), any(StoragePath.class), any()))
        .thenReturn(BaseUsages.ofDisk(10), BaseUsages.ofDisk(2));

    final var usages =
        this.service.writePackage(
            REPO_ID,
            "Some.Package",
            "1.0.0+Build",
            new ByteArrayInputStream(new byte[] {1}),
            new byte[] {2});

    final var paths = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy, times(2)).write(eq(REPO_ID.toString()), paths.capture(), any());
    assertThat(paths.getAllValues())
        .extracting(AbstractNuGetStorageServiceTest::relativePath)
        .containsExactly(CANONICAL_NUPKG, CANONICAL_NUSPEC);
    assertThat(usages.getDiskUsage()).isEqualTo(12);
  }

  @Test
  @DisplayName("reads a version without build metadata from its canonical directory")
  void readsCanonicalVersion() {
    final Resource resource = new ByteArrayResource(new byte[] {1});
    this.stubOnlyExisting(CANONICAL_NUPKG, resource);

    assertThat(this.service.getNuPkg(REPO_ID, "Some.Package", "1.0")).isSameAs(resource);
  }

  @Test
  @DisplayName("does not read the directory a version with build metadata used to be stored under")
  void ignoresLegacyDirectory() {
    this.stubOnlyExisting(LEGACY_NUPKG, new ByteArrayResource(new byte[] {1}));

    assertThatThrownBy(() -> this.service.getNuPkg(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isInstanceOf(ItemNotFoundException.class);
    assertThatThrownBy(() -> this.service.getNuspec(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("reads a version with build metadata from its canonical directory")
  void readsBuildMetadataFromCanonicalDirectory() {
    final Resource canonical = new ByteArrayResource(new byte[] {1});
    this.stubOnlyExisting(CANONICAL_NUPKG, canonical);

    assertThat(this.service.getNuPkg(REPO_ID, "Some.Package", "1.0.0+Build")).isSameAs(canonical);
  }

  @Test
  @DisplayName("reads a nuspec with the same version rules as a nupkg")
  void readsNuspec() {
    final Resource canonical = new ByteArrayResource(new byte[] {1});
    this.stubOnlyExisting(CANONICAL_NUSPEC, canonical);

    assertThat(this.service.getNuspec(REPO_ID, "Some.Package", "1.0.0+Build")).isSameAs(canonical);
  }

  @Test
  @DisplayName("answers not found when the canonical directory holds no file")
  void notFound() {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.getNuPkg(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isInstanceOf(ItemNotFoundException.class);
    assertThatThrownBy(() -> this.service.getNuspec(REPO_ID, "Some.Package", "1.0.0"))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("looks in one place only, whatever the build metadata of the version")
  void singleLookup() {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.getNuPkg(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.storageStrategy).get(any(StoragePath.class), anyString());
  }

  @Test
  @DisplayName("points the scanner at the canonical path, also for a version with build metadata")
  void relativePathIsCanonical() {
    assertThat(this.service.getNupkgRelativePath("Some.Package", "1.0.0+Build"))
        .isEqualTo(CANONICAL_NUPKG);
    assertThat(this.service.getNupkgRelativePath("Some.Package", "1.0")).isEqualTo(CANONICAL_NUPKG);
  }

  @Test
  @DisplayName("deletes the canonical directory of a version with build metadata")
  void deletesCanonicalDirectoryOfBuildMetadataVersion() throws IOException {
    when(this.storageStrategy.calculatePathUsage(any(StoragePath.class))).thenReturn(7L);

    assertThat(this.service.deletePackageVersion(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isEqualTo(7L);

    final var deleted = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy).delete(deleted.capture());
    assertThat(relativePath(deleted.getValue())).isEqualTo("packages/some.package/1.0.0");
  }

  @Test
  @DisplayName("deletes the directory a version with build metadata was stored under, only")
  void deletesBuildMetadataDirectory() throws IOException {
    when(this.storageStrategy.calculatePathUsage(any(StoragePath.class))).thenReturn(7L);

    assertThat(this.service.deleteBuildMetadataVersion(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isEqualTo(7L);

    final var deleted = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy).delete(deleted.capture());
    assertThat(relativePath(deleted.getValue())).isEqualTo("packages/some.package/1.0.0+build");
  }

  @Test
  @DisplayName("never deletes the canonical directory as a directory with build metadata")
  void keepsCanonicalDirectoryWhenThereIsNoBuildMetadata() throws IOException {
    assertThat(this.service.deleteBuildMetadataVersion(REPO_ID, "Some.Package", "1.0")).isZero();

    verifyNoInteractions(this.storageStrategy);
  }

  @Test
  @DisplayName("deletes the canonical directory of a version without build metadata")
  void deletesCanonicalDirectory() throws IOException {
    when(this.storageStrategy.calculatePathUsage(any(StoragePath.class))).thenReturn(3L);

    this.service.deletePackageVersion(REPO_ID, "Some.Package", "1.0");

    final var deleted = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy).delete(deleted.capture());
    assertThat(relativePath(deleted.getValue())).isEqualTo("packages/some.package/1.0.0");
  }

  @Test
  @DisplayName("copies the files of a version with build metadata to its canonical directory")
  void copiesLegacyFilesToCanonicalDirectory() throws IOException {
    when(this.storageStrategy.get(any(StoragePath.class), eq(REPO_ID.toString())))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new ByteArrayResource(relativePath(invocation.getArgument(0)).getBytes())));

    assertThat(this.service.copyToCanonicalVersion(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isTrue();

    final var read = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy, times(2)).get(read.capture(), eq(REPO_ID.toString()));
    assertThat(read.getAllValues())
        .extracting(AbstractNuGetStorageServiceTest::relativePath)
        .containsExactly(LEGACY_NUPKG, LEGACY_NUPKG.replace(".nupkg", ".nuspec"));

    final var written = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy, times(2)).write(eq(REPO_ID.toString()), written.capture(), any());
    assertThat(written.getAllValues())
        .extracting(AbstractNuGetStorageServiceTest::relativePath)
        .containsExactly(CANONICAL_NUPKG, CANONICAL_NUSPEC);
    verify(this.storageStrategy, never()).delete(any(StoragePath.class));
  }

  @Test
  @DisplayName("copies the content of the legacy file as it is")
  void copiesContent() throws IOException {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {4, 2})));

    this.service.copyToCanonicalVersion(REPO_ID, "Some.Package", "1.0.0+Build");

    final var content = ArgumentCaptor.forClass(java.io.InputStream.class);
    verify(this.storageStrategy, times(2))
        .write(anyString(), any(StoragePath.class), content.capture());
    for (final var stream : content.getAllValues()) {
      assertThat(stream).hasBinaryContent(new byte[] {4, 2});
    }
  }

  @Test
  @DisplayName("reports that nothing was copied when the legacy directory holds no nupkg")
  void copiesNothingWhenLegacyFilesAreMissing() throws IOException {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.empty());

    assertThat(this.service.copyToCanonicalVersion(REPO_ID, "Some.Package", "1.0.0+Build"))
        .isFalse();

    verify(this.storageStrategy, never()).write(anyString(), any(StoragePath.class), any());
  }

  @Test
  @DisplayName("has nothing to copy for a version without build metadata")
  void copiesNothingForCanonicalVersion() throws IOException {
    assertThat(this.service.copyToCanonicalVersion(REPO_ID, "Some.Package", "1.0")).isFalse();

    verifyNoInteractions(this.storageStrategy);
  }

  @Test
  @DisplayName("deletes a whole package and a whole repo")
  void deletesPackageAndRepo() throws IOException {
    this.service.deletePackage(REPO_ID, "Some.Package");
    this.service.deleteRepo(REPO_ID);

    final var deleted = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy, times(2)).delete(deleted.capture());
    assertThat(deleted.getAllValues())
        .extracting(StoragePath::getPath)
        .containsExactly(REPO_ID + "/packages/some.package", REPO_ID.toString());
  }
}
