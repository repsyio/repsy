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
package io.repsy.protocols.golang.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractGoStorageService")
class AbstractGoStorageServiceTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "go";
  private static final StoragePath VERSION_BASE =
      StoragePath.of(STORAGE_KEY, "/github.com/acme/tool/@v/v1.0.0");

  @Mock private StorageStrategy storageStrategy;

  private AbstractGoStorageService<UUID> service;

  @BeforeEach
  void setUp() {
    service = new TestStorageService(storageStrategy);
  }

  static class TestStorageService extends AbstractGoStorageService<UUID> {

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  private static StorageItemInfo file(final String name) {
    return StorageItemInfo.builder().name(name).directory(false).build();
  }

  private static StoragePath pathEndingWith(final String suffix) {
    return argThat(p -> p != null && p.getPath().endsWith(suffix));
  }

  @Test
  @DisplayName("deleteVersionFiles() deletes only the files of that exact version")
  void deletesOnlyFilesOfTheVersion() {
    when(storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenReturn(
            List.of(
                file("v1.0.0.info"),
                file("v1.0.0.mod"),
                file("v1.0.0.zip"),
                file("v1.0.0-rc.1.zip"),
                file("v1.0.01.zip"),
                StorageItemInfo.builder().name("v1.0.0.d").directory(true).build()));

    service.deleteVersionFiles(VERSION_BASE, REPO_NAME);

    verify(storageStrategy).delete(pathEndingWith("/@v/v1.0.0.info"));
    verify(storageStrategy).delete(pathEndingWith("/@v/v1.0.0.mod"));
    verify(storageStrategy).delete(pathEndingWith("/@v/v1.0.0.zip"));
    verify(storageStrategy, never()).delete(pathEndingWith("v1.0.0-rc.1.zip"));
    verify(storageStrategy, never()).delete(pathEndingWith("v1.0.01.zip"));
    verify(storageStrategy, never()).delete(pathEndingWith("v1.0.0.d"));
  }

  @Test
  @DisplayName("deleteVersionFiles() does nothing when the @v directory does not exist")
  void ignoresMissingDirectory() {
    when(storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));

    assertThatCode(() -> service.deleteVersionFiles(VERSION_BASE, REPO_NAME))
        .doesNotThrowAnyException();

    verify(storageStrategy, never()).delete(any(StoragePath.class));
  }

  @Test
  @DisplayName("deleteVersionFiles() swallows any other listing failure")
  void ignoresUnexpectedListingFailure() {
    when(storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenThrow(new IllegalStateException("storage down"));

    assertThatCode(() -> service.deleteVersionFiles(VERSION_BASE, REPO_NAME))
        .doesNotThrowAnyException();

    verify(storageStrategy, never()).delete(any(StoragePath.class));
  }

  @Test
  @DisplayName("deleteVersionFiles() keeps deleting the other files when one delete fails")
  void continuesAfterFailedFileDelete() {
    when(storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenReturn(List.of(file("v1.0.0.info"), file("v1.0.0.mod"), file("v1.0.0.zip")));
    doThrow(new IllegalStateException("disk error"))
        .when(storageStrategy)
        .delete(pathEndingWith("/@v/v1.0.0.mod"));

    assertThatCode(() -> service.deleteVersionFiles(VERSION_BASE, REPO_NAME))
        .doesNotThrowAnyException();

    verify(storageStrategy).delete(pathEndingWith("/@v/v1.0.0.info"));
    verify(storageStrategy).delete(pathEndingWith("/@v/v1.0.0.zip"));
  }

  @Test
  @DisplayName("deleteVersionFiles() ignores a path without a storage key or a slash")
  void ignoresPathsItCannotResolve() {
    service.deleteVersionFiles(StoragePath.ofPath("no-storage-key"), REPO_NAME);
    service.deleteVersionFiles(StoragePath.of(STORAGE_KEY, "no-slash"), REPO_NAME);

    verify(storageStrategy, never()).listDirectoryContents(any(StoragePath.class));
  }
}
