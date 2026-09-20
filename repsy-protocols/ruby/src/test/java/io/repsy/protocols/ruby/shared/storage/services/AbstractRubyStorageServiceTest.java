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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
