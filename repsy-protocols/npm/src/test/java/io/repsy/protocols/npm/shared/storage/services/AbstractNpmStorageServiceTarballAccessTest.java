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
package io.repsy.protocols.npm.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.storage.RepoRef;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/** Pins the repo and tarball reads of the storage service before they are split (RPS-2061). */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService repo and tarball access")
class AbstractNpmStorageServiceTarballAccessTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Mock private StorageStrategy storageStrategy;

  private AbstractNpmStorageService service;

  @BeforeEach
  void setUp() {
    this.service = new AbstractNpmStorageService(this.storageStrategy) {};
  }

  @Test
  @DisplayName("createRepo creates the directory of the repo id")
  void createsRepo() {
    this.service.createRepo(REPO_ID);

    verify(this.storageStrategy).createDirectory(REPO_ID.toString());
  }

  @Test
  @DisplayName("deleteRepo deletes the root of the repo id")
  void deletesRepo() {
    this.service.deleteRepo(REPO_ID);

    verify(this.storageStrategy)
        .delete(argThat((StoragePath path) -> path.getPath().equals(REPO_ID.toString())));
  }

  @Test
  @DisplayName("getTarball resolves the scoped package path and the file name")
  void getsTarball() {
    final var resource = new ByteArrayResource(new byte[] {1});
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.of(resource));

    assertThat(this.service.getTarball(new RepoRef(REPO_ID, "r"), "foo", "demo", "demo-1.0.0.tgz"))
        .isSameAs(resource);
    verify(this.storageStrategy)
        .get(
            argThat(
                (StoragePath path) -> path.getPath().equals(REPO_ID + "/foo/demo/demo-1.0.0.tgz")),
            eq("r"));
  }

  @Test
  @DisplayName("getTarball is not found when there is no file")
  void tarballMissing() {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.service.getTarball(new RepoRef(REPO_ID, "r"), null, "demo", "demo-1.0.0.tgz"))
        .isInstanceOf(ItemNotFoundException.class);
  }
}
