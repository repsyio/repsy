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
package io.repsy.protocols.cargo.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoStorageService")
class AbstractCargoStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "cargo";
  private static final String CRATE_PATH = REPO_ID + "/crates/serde/serde-1.0.0.crate";
  private static final String INDEX_PATH = REPO_ID + "/index/se/rd/serde";

  @Mock private StorageStrategy storageStrategy;

  private AbstractCargoStorageService service;

  @BeforeEach
  void setUp() {
    service = new TestStorageService(storageStrategy);
  }

  static class TestStorageService extends AbstractCargoStorageService {

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  private static StoragePath path(final String expected) {
    return argThat(p -> p != null && expected.equals(p.getPath()));
  }

  private static String read(final InputStream in) {
    try {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  @DisplayName("writeCrateAndIndex()")
  class WriteCrateAndIndexTests {

    @Test
    @DisplayName("writes the crate, appends the index line and sums the disk usage")
    void writesCrateAndAppendsIndex() throws IOException {
      final var crateBytes = new byte[] {1, 2, 3};
      final var written = new byte[1][];
      when(storageStrategy.write(eq(REPO_NAME), path(CRATE_PATH), any(InputStream.class)))
          .thenAnswer(
              inv -> {
                written[0] = inv.getArgument(2, InputStream.class).readAllBytes();
                return BaseUsages.ofDisk(100);
              });
      when(storageStrategy.append(eq(REPO_NAME), path(INDEX_PATH), any(byte[].class)))
          .thenReturn(BaseUsages.ofDisk(20));

      final var usages =
          service.writeCrateAndIndex(
              REPO_ID,
              REPO_NAME,
              "serde",
              "1.0.0",
              new ByteArrayInputStream(crateBytes),
              "{\"vers\":\"1.0.0\"}");

      assertThat(usages.getDiskUsage()).isEqualTo(120);
      assertThat(written[0]).containsExactly(crateBytes);

      final var line = ArgumentCaptor.forClass(byte[].class);
      verify(storageStrategy).append(eq(REPO_NAME), path(INDEX_PATH), line.capture());
      assertThat(new String(line.getValue(), StandardCharsets.UTF_8))
          .isEqualTo("{\"vers\":\"1.0.0\"}\n");
    }

    @ParameterizedTest(name = "crate ''{0}'' is indexed at ''{1}''")
    @CsvSource({
      "a,      index/1/a",
      "ab,     index/2/ab",
      "abc,    index/3/a/abc",
      "abcd,   index/ab/cd/abcd",
      "my_crate, index/my/_c/my_crate"
    })
    @DisplayName("uses the cargo sparse index layout for the index file path")
    void usesSparseIndexLayout(final String crateName, final String indexPath) throws IOException {
      when(storageStrategy.write(eq(REPO_NAME), any(StoragePath.class), any(InputStream.class)))
          .thenReturn(BaseUsages.ofDisk(1));
      when(storageStrategy.append(eq(REPO_NAME), any(StoragePath.class), any(byte[].class)))
          .thenReturn(BaseUsages.ofDisk(1));

      service.writeCrateAndIndex(
          REPO_ID, REPO_NAME, crateName, "0.1.0", new ByteArrayInputStream(new byte[0]), "{}");

      verify(storageStrategy).append(eq(REPO_NAME), path(REPO_ID + "/" + indexPath), any());
    }
  }

  @Nested
  @DisplayName("getCrate()")
  class GetCrateTests {

    @Test
    @DisplayName("returns the stored crate resource")
    void returnsCrate() {
      final var resource = new ByteArrayResource(new byte[] {1});
      when(storageStrategy.get(path(CRATE_PATH), eq(REPO_NAME))).thenReturn(Optional.of(resource));

      assertThat(service.getCrate(REPO_ID, REPO_NAME, "serde", "1.0.0")).isSameAs(resource);
    }

    @Test
    @DisplayName("throws crateNotFound when the crate is missing")
    void throwsWhenMissing() {
      when(storageStrategy.get(path(CRATE_PATH), eq(REPO_NAME))).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getCrate(REPO_ID, REPO_NAME, "serde", "1.0.0"))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("crateNotFound");
    }
  }

  @Test
  @DisplayName("deleteCrate() deletes the crate file and returns its usage")
  void deleteCrate() throws IOException {
    when(storageStrategy.getFileUsage(path(CRATE_PATH), eq(REPO_NAME))).thenReturn(55L);

    assertThat(service.deleteCrate(REPO_ID, REPO_NAME, "serde", "1.0.0")).isEqualTo(55L);
    verify(storageStrategy).delete(path(CRATE_PATH));
  }

  @Nested
  @DisplayName("deletePackage()")
  class DeletePackageTests {

    @Test
    @DisplayName("deletes the crate directory and index file, returning combined usage")
    void deletesCrateDirAndIndex() throws IOException {
      final var crateDir = REPO_ID + "/crates/serde";
      when(storageStrategy.calculatePathUsage(path(crateDir))).thenReturn(300L);
      when(storageStrategy.getFileUsage(path(INDEX_PATH), eq(REPO_NAME))).thenReturn(40L);

      assertThat(service.deletePackage(REPO_ID, REPO_NAME, "serde")).isEqualTo(340L);
      verify(storageStrategy).delete(path(crateDir));
      verify(storageStrategy).delete(path(INDEX_PATH));
    }

    @Test
    @DisplayName("wraps IOExceptions in ErrorOccurredException")
    void wrapsIoException() throws IOException {
      when(storageStrategy.getFileUsage(path(INDEX_PATH), eq(REPO_NAME)))
          .thenThrow(new IOException("disk"));

      assertThatThrownBy(() -> service.deletePackage(REPO_ID, REPO_NAME, "serde"))
          .isInstanceOf(ErrorOccurredException.class)
          .hasMessage("errorOccurred")
          .hasCauseInstanceOf(IOException.class);
      verify(storageStrategy, never()).delete(any());
    }
  }

  @Nested
  @DisplayName("rewriteIndex()")
  class RewriteIndexTests {

    @Test
    @DisplayName("rewrites the index file with newline-terminated JSON lines")
    void rewritesIndex() throws IOException {
      final var content = new String[1];
      when(storageStrategy.write(eq(REPO_NAME), path(INDEX_PATH), any(InputStream.class)))
          .thenAnswer(
              inv -> {
                content[0] = read(inv.getArgument(2, InputStream.class));
                return BaseUsages.ofDisk(77);
              });

      final var usage =
          service.rewriteIndex(REPO_ID, REPO_NAME, "serde", List.of("{\"a\":1}", "{\"b\":2}"));

      assertThat(usage).isEqualTo(77L);
      assertThat(content[0]).isEqualTo("{\"a\":1}\n{\"b\":2}\n");
    }

    @Test
    @DisplayName("deletes the index file and returns negative usage when no lines remain")
    void deletesIndexWhenEmpty() throws IOException {
      when(storageStrategy.getFileUsage(path(INDEX_PATH), eq(REPO_NAME))).thenReturn(40L);

      assertThat(service.rewriteIndex(REPO_ID, REPO_NAME, "serde", List.of())).isEqualTo(-40L);
      verify(storageStrategy).delete(path(INDEX_PATH));
      verify(storageStrategy, never()).write(any(), any(), any());
    }
  }

  @Test
  @DisplayName("createRepo() creates the repo directory")
  void createRepo() {
    service.createRepo(REPO_ID);

    verify(storageStrategy).createDirectory(REPO_ID.toString());
  }

  @Test
  @DisplayName("deleteRepo() deletes the repo directory without sizing it first")
  void deleteRepo() {
    service.deleteRepo(REPO_ID);

    verify(storageStrategy).delete(path(REPO_ID.toString()));
    verify(storageStrategy, never()).calculatePathUsage(any());
  }

  @Test
  @DisplayName("getCrateRelativePath() returns crates/{name}/{name}-{version}.crate")
  void getCrateRelativePath() {
    assertThat(service.getCrateRelativePath("serde", "1.0.0"))
        .isEqualTo("crates/serde/serde-1.0.0.crate");
  }
}
