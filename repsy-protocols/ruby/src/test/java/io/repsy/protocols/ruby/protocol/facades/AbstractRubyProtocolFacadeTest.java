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
package io.repsy.protocols.ruby.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyProtocolFacade publishGem")
class AbstractRubyProtocolFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "gems";

  private static final String GEMSPEC =
      """
      --- !ruby/object:Gem::Specification
      name: demo
      version: !ruby/object:Gem::Version
        version: 1.2.3
      platform: ruby
      """;

  @Mock private RubyGemProtocolService<UUID> gemService;
  @Mock private RubyStorageService storageService;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractRubyProtocolFacade<UUID> {

    TestFacade(
        final RubyGemProtocolService<UUID> gemService, final RubyStorageService storageService) {
      super(gemService, storageService);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade = new TestFacade(this.gemService, this.storageService);

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

  private static byte[] gem(final String yaml) throws IOException {
    final var gzipped = new ByteArrayOutputStream();
    try (final var out = new GZIPOutputStream(gzipped)) {
      out.write(yaml.getBytes(StandardCharsets.UTF_8));
    }

    final var tarBytes = new ByteArrayOutputStream();
    try (final var tar = new TarArchiveOutputStream(tarBytes)) {
      final var entry = new TarArchiveEntry("metadata.gz");
      entry.setSize(gzipped.size());
      tar.putArchiveEntry(entry);
      tar.write(gzipped.toByteArray());
      tar.closeArchiveEntry();
    }
    return tarBytes.toByteArray();
  }

  @Test
  @DisplayName("stores the gem as streamed from the spooled file and records it with its SHA-256")
  void storesGemAndRecordsIt() throws Exception {
    final var gemBytes = gem(GEMSPEC);
    final var stored = new AtomicReference<byte[]>();
    when(this.storageService.writeGem(
            eq(REPO_ID), eq(REPO_NAME), eq("demo"), eq("1.2.3"), eq("ruby"), any()))
        .thenAnswer(
            invocation -> {
              stored.set(invocation.<java.io.InputStream>getArgument(5).readAllBytes());
              return BaseUsages.ofDisk(gemBytes.length);
            });

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gemBytes))) {
      this.facade.publishGem(this.context, upload);

      final var metadata = ArgumentCaptor.forClass(GemMetadata.class);
      verify(this.gemService).publishGem(any(), metadata.capture(), eq(upload.sha256Hex()));
      assertThat(metadata.getValue().getName()).isEqualTo("demo");
    }

    assertThat(stored.get()).isEqualTo(gemBytes);
    assertThat(this.context.<String>getProperty("gemName")).isEqualTo("demo");
    assertThat(this.context.<String>getProperty("gemVersion")).isEqualTo("1.2.3");
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage())
        .isEqualTo(gemBytes.length);
    verify(this.gemService).getCompactEntriesByGemName(any(), eq("demo"));
  }

  @Test
  @DisplayName("stores nothing when the gem has no metadata")
  void storesNothingForInvalidGem() throws Exception {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(new byte[2048]))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("invalidGemFile");
    }

    verify(this.storageService, never()).writeGem(any(), any(), any(), any(), any(), any());
    verify(this.gemService, never()).publishGem(any(), any(), any());
  }
}
