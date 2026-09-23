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
package io.repsy.protocols.pypi.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import freemarker.template.Configuration;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractPypiStorageService")
class AbstractPypiStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "pypi";
  private static final String NORMALIZED_NAME = "my-package";
  private static final String FILENAME = "my_package-1.0.0-py3-none-any.whl";

  @Mock private StorageStrategy storageStrategy;
  @Mock private Configuration freeMarkerConfiguration;

  private AbstractPypiStorageService<UUID> service;

  @BeforeEach
  void setUp() {
    service = new TestStorageService(storageStrategy, freeMarkerConfiguration);
  }

  static class TestStorageService extends AbstractPypiStorageService<UUID> {

    TestStorageService(final StorageStrategy storageStrategy, final Configuration configuration) {
      super(storageStrategy, configuration);
    }

    @Override
    protected String buildRepoUri(final BaseRepoInfo<UUID> baseRepoInfo) {
      return "https://example.test/" + baseRepoInfo.getName();
    }
  }

  private static StoragePath path(final String expected) {
    return argThat(p -> p != null && expected.equals(p.getPath()));
  }

  @Nested
  @DisplayName("isPackageFileExist() (RPS-1223)")
  class IsPackageFileExistTests {

    @Test
    @DisplayName(
        "returns true when storage already has a file at that exact path -- the check is keyed"
            + " purely on repoId/normalizedName/filename, with no version parameter to bypass it")
    void returnsTrueWhenStorageHasTheFile() {
      when(storageStrategy.get(
              path(REPO_ID + "/" + NORMALIZED_NAME + "/" + FILENAME), eq(NORMALIZED_NAME)))
          .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

      final var exists = service.isPackageFileExist(REPO_ID, NORMALIZED_NAME, FILENAME);

      assertThat(exists).isTrue();
      verify(storageStrategy).get(any(StoragePath.class), eq(NORMALIZED_NAME));
    }

    @Test
    @DisplayName("returns false when storage has nothing at that path")
    void returnsFalseWhenStorageHasNothing() {
      when(storageStrategy.get(any(StoragePath.class), eq(NORMALIZED_NAME)))
          .thenReturn(Optional.empty());

      final var exists = service.isPackageFileExist(REPO_ID, NORMALIZED_NAME, FILENAME);

      assertThat(exists).isFalse();
    }
  }

  @Nested
  @DisplayName("discardArchive() (RPS-1124)")
  class DiscardArchiveTests {

    private static final String ARCHIVE_PATH = REPO_ID + "/" + NORMALIZED_NAME + "/" + FILENAME;
    private static final String DIGEST_PATH = ARCHIVE_PATH + ".sha256";

    @Test
    @DisplayName("deletes the archive and its digest when both are there")
    void deletesBothFiles() {
      when(storageStrategy.get(any(StoragePath.class), eq(REPO_NAME)))
          .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

      service.discardArchive(REPO_ID, REPO_NAME, NORMALIZED_NAME, FILENAME);

      verify(storageStrategy).delete(path(ARCHIVE_PATH));
      verify(storageStrategy).delete(path(DIGEST_PATH));
    }

    @Test
    @DisplayName("deletes only what exists: a write that failed before the digest has no digest")
    void deletesOnlyTheFileThatExists() {
      when(storageStrategy.get(path(ARCHIVE_PATH), eq(REPO_NAME)))
          .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));
      when(storageStrategy.get(path(DIGEST_PATH), eq(REPO_NAME))).thenReturn(Optional.empty());

      service.discardArchive(REPO_ID, REPO_NAME, NORMALIZED_NAME, FILENAME);

      verify(storageStrategy).delete(path(ARCHIVE_PATH));
      verify(storageStrategy, never()).delete(path(DIGEST_PATH));
    }

    @Test
    @DisplayName("does nothing when the write failed before it created any file")
    void doesNothingWhenNothingWasWritten() {
      when(storageStrategy.get(any(StoragePath.class), eq(REPO_NAME))).thenReturn(Optional.empty());

      service.discardArchive(REPO_ID, REPO_NAME, NORMALIZED_NAME, FILENAME);

      verify(storageStrategy, never()).delete(any(StoragePath.class));
    }
  }

  @Nested
  @DisplayName("deleteRelease() still filters by isFileBelongsRelease")
  class DeleteReleaseTests {

    @Test
    @DisplayName("deletes only archive files belonging to the given release version")
    void deletesOnlyMatchingReleaseFiles() {
      final var matching =
          io.repsy.libs.storage.core.dtos.StorageItemInfo.builder()
              .name("my_package-1.0.0-py3-none-any.whl")
              .directory(false)
              .size(10L)
              .build();
      final var nonMatching =
          io.repsy.libs.storage.core.dtos.StorageItemInfo.builder()
              .name("my_package-2.0.0-py3-none-any.whl")
              .directory(false)
              .size(20L)
              .build();
      when(storageStrategy.listDirectoryContents(any(StoragePath.class)))
          .thenReturn(java.util.List.of(matching, nonMatching));

      final var usage = service.deleteRelease(REPO_ID, NORMALIZED_NAME, "1.0.0");

      assertThat(usage).isEqualTo(10L);
      verify(storageStrategy)
          .delete(path(REPO_ID + "/" + NORMALIZED_NAME + "/my_package-1.0.0-py3-none-any.whl"));
      verify(storageStrategy, never())
          .delete(path(REPO_ID + "/" + NORMALIZED_NAME + "/my_package-2.0.0-py3-none-any.whl"));
    }
  }

  @Nested
  @DisplayName("writePackageArchive() (RPS-1224/RPS-1225)")
  class WritePackageArchiveTests {

    @Test
    @DisplayName("stores exactly the digest carried on the upload form in the .sha256 sidecar")
    void storesTheFormsDigestVerbatimInTheSidecar() throws IOException {
      final var uploadForm = new PackageUploadForm();
      uploadForm.setNormalizedName(NORMALIZED_NAME);
      // As set by the facade before this is ever called: the server-computed, lowercase digest.
      uploadForm.setSha256_digest("cafef00d");
      final var file = new MockMultipartFile("content", FILENAME, null, new byte[] {1, 2, 3});

      when(storageStrategy.write(eq(REPO_NAME), any(StoragePath.class), any(InputStream.class)))
          .thenReturn(BaseUsages.ofDisk(100));

      final var sidecarBytes = new byte[1][];
      when(storageStrategy.write(
              eq(REPO_NAME),
              path(REPO_ID + "/" + NORMALIZED_NAME + "/" + FILENAME + ".sha256"),
              any()))
          .thenAnswer(
              inv -> {
                sidecarBytes[0] = inv.getArgument(2, InputStream.class).readAllBytes();
                return BaseUsages.ofDisk(8);
              });

      final var usages = service.writePackageArchive(REPO_ID, REPO_NAME, uploadForm, file);

      assertThat(usages.getDiskUsage()).isEqualTo(108);
      assertThat(new String(sidecarBytes[0], StandardCharsets.UTF_8)).isEqualTo("cafef00d");
    }

    @Test
    @DisplayName("throws rather than write a null digest -- the facade must validate first")
    void throwsOnNullDigestInsteadOfNpe() {
      final var uploadForm = new PackageUploadForm();
      uploadForm.setNormalizedName(NORMALIZED_NAME);
      uploadForm.setSha256_digest(null);
      final var file = new MockMultipartFile("content", FILENAME, null, new byte[] {1, 2, 3});

      when(storageStrategy.write(eq(REPO_NAME), any(StoragePath.class), any(InputStream.class)))
          .thenReturn(BaseUsages.ofDisk(100));

      assertThatThrownBy(() -> service.writePackageArchive(REPO_ID, REPO_NAME, uploadForm, file))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
