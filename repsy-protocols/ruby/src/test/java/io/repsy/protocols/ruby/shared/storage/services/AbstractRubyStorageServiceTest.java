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
package io.repsy.protocols.ruby.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyStorageService")
class AbstractRubyStorageServiceTest {

  @Mock private StorageStrategy storageStrategy;

  private static class TestService extends AbstractRubyStorageService {

    TestService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  @Test
  @DisplayName("writeGem() streams the gem to storage under its file name")
  void writesGemFromStream() {
    final var repoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final var gem = new byte[] {1, 2, 3};
    final var written = new AtomicReference<byte[]>();
    final var path = new AtomicReference<StoragePath>();
    when(this.storageStrategy.write(eq("gems"), any(), any()))
        .thenAnswer(
            invocation -> {
              path.set(invocation.getArgument(1));
              written.set(invocation.<InputStream>getArgument(2).readAllBytes());
              return BaseUsages.ofDisk(3);
            });

    final var usages =
        new TestService(this.storageStrategy)
            .writeGem(repoId, "gems", "rack", "2.2.8", "ruby", new ByteArrayInputStream(gem));

    assertThat(usages.getDiskUsage()).isEqualTo(3);
    assertThat(written.get()).isEqualTo(gem);
    assertThat(path.get().getRelativePath().getPath()).endsWith("rack/rack-2.2.8.gem");
  }

  @Test
  @DisplayName(
      "getGem() builds the path from the given gem name, never parses it from the filename")
  void getsGemFromGivenFields() {
    final var repoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final var resource = new ByteArrayResource(new byte[] {9});
    final var path = new AtomicReference<StoragePath>();
    when(this.storageStrategy.get(any(), eq("gems")))
        .thenAnswer(
            invocation -> {
              path.set(invocation.getArgument(0));
              return Optional.of(resource);
            });

    // A gem name that itself contains a hyphen-digit sequence (RPS-1236): the naive
    // first-boundary split would misread this as name "x" / version "2fa-1.0.0".
    final var found =
        new TestService(this.storageStrategy).getGem(repoId, "gems", "x-2fa", "1.0.0", "ruby");

    assertThat(found).isSameAs(resource);
    assertThat(path.get().getRelativePath().getPath()).endsWith("x-2fa/x-2fa-1.0.0.gem");
  }

  @Test
  @DisplayName("getGem() builds the platform-suffixed filename for a non-default platform")
  void getsPlatformGem() {
    final var repoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final var resource = new ByteArrayResource(new byte[] {9});
    final var path = new AtomicReference<StoragePath>();
    when(this.storageStrategy.get(any(), eq("gems")))
        .thenAnswer(
            invocation -> {
              path.set(invocation.getArgument(0));
              return Optional.of(resource);
            });

    new TestService(this.storageStrategy).getGem(repoId, "gems", "nokogiri", "1.16.0", "java");

    assertThat(path.get().getRelativePath().getPath())
        .endsWith("nokogiri/nokogiri-1.16.0-java.gem");
  }

  @Test
  @DisplayName("getGem() throws gemNotFound when storage has nothing at that path")
  void throwsWhenGemFileMissing() {
    final var repoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(this.storageStrategy.get(any(), eq("gems"))).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                new TestService(this.storageStrategy)
                    .getGem(repoId, "gems", "demo", "1.0.0", "ruby"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessageContaining("gemNotFound");
  }

  @Test
  @DisplayName("buildFilename() omits the platform for the default ruby platform")
  void omitsDefaultPlatform() {
    assertThat(AbstractRubyStorageService.buildFilename("rack", "2.2.8", "ruby"))
        .isEqualTo("rack-2.2.8.gem");
  }

  @Test
  @DisplayName("buildFilename() appends a non-default platform")
  void appendsNonDefaultPlatform() {
    assertThat(AbstractRubyStorageService.buildFilename("nokogiri", "1.16.0", "x86_64-linux"))
        .isEqualTo("nokogiri-1.16.0-x86_64-linux.gem");
  }
}
