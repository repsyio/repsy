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
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
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

  /** Makes the mocked service run the file writer like the real one does after the row. */
  private void publishRunsFileWriter(final boolean replacesExisting) throws IOException {
    when(this.gemService.publishGem(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<RubyGemProtocolService.GemFileWriter>getArgument(3)
                    .write(replacesExisting));
  }

  @Test
  @DisplayName("stores the gem as streamed from the spooled file and records it with its SHA-256")
  void storesGemAndRecordsIt() throws Exception {
    final var gemBytes = gem(GEMSPEC);
    final var stored = new AtomicReference<byte[]>();
    this.publishRunsFileWriter(false);
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
      verify(this.gemService).publishGem(any(), metadata.capture(), eq(upload.sha256Hex()), any());
      assertThat(metadata.getValue().getName()).isEqualTo("demo");
    }

    assertThat(stored.get()).isEqualTo(gemBytes);
    assertThat(this.context.<String>getProperty("gemName")).isEqualTo("demo");
    assertThat(this.context.<String>getProperty("gemVersion")).isEqualTo("1.2.3");
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage())
        .isEqualTo(gemBytes.length);
    verify(this.gemService).getCompactEntriesByGemName(any(), eq("demo"));
    verify(this.gemService).saveVersionsChecksum(any(), eq("demo"), any());
    verify(this.storageService, never()).deleteGem(any(), any(), any(), any(), any());
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
    verify(this.gemService, never()).publishGem(any(), any(), any(), any());
  }

  @Test
  @DisplayName("does not write the file when the row is rejected, and reports nothing")
  void writesNothingWhenTheRowIsRejected() throws Exception {
    when(this.gemService.publishGem(any(), any(), any(), any()))
        .thenThrow(new ItemAlreadyExistException("gemVersionAlreadyExists"));

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gem(GEMSPEC)))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload))
          .isInstanceOf(ItemAlreadyExistException.class);
    }

    verify(this.storageService, never()).writeGem(any(), any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
    assertThat(this.context.<String>getProperty("gemName")).isNull();
  }

  @Test
  @DisplayName("does not write the file when the versions checksum cannot be refreshed")
  void writesNothingWhenTheChecksumRefreshFails() throws Exception {
    this.publishRunsFileWriter(false);
    when(this.gemService.getCompactEntriesByGemName(any(), eq("demo")))
        .thenThrow(new IllegalStateException("database went away"));

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gem(GEMSPEC)))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload))
          .isInstanceOf(IllegalStateException.class);
    }

    verify(this.storageService, never()).writeGem(any(), any(), any(), any(), any(), any());
    verify(this.storageService, never()).deleteGem(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("removes the partly written file of a new version when the write fails")
  void removesThePartialFileOfANewVersion() throws Exception {
    this.publishRunsFileWriter(false);
    final var failure = new IllegalStateException("disk full");
    when(this.storageService.writeGem(any(), any(), any(), any(), any(), any())).thenThrow(failure);

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gem(GEMSPEC)))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload)).isSameAs(failure);
    }

    verify(this.storageService)
        .deleteGem(eq(REPO_ID), eq(REPO_NAME), eq("demo"), eq("1.2.3"), eq("ruby"));
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("keeps the file of the version being replaced when the write fails")
  void keepsTheFileOfAReplacedVersion() throws Exception {
    this.publishRunsFileWriter(true);
    final var failure = new IllegalStateException("disk full");
    when(this.storageService.writeGem(any(), any(), any(), any(), any(), any())).thenThrow(failure);

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gem(GEMSPEC)))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload)).isSameAs(failure);
    }

    verify(this.storageService, never()).deleteGem(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("reports the write failure, not the cleanup failure, when nothing was written")
  void keepsTheWriteFailureWhenTheCleanupFails() throws Exception {
    this.publishRunsFileWriter(false);
    final var failure = new IllegalStateException("disk full");
    final var cleanupFailure = new ItemNotFoundException("gemNotFound");
    when(this.storageService.writeGem(any(), any(), any(), any(), any(), any())).thenThrow(failure);
    when(this.storageService.deleteGem(any(), any(), any(), any(), any()))
        .thenThrow(cleanupFailure);

    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(gem(GEMSPEC)))) {
      assertThatThrownBy(() -> this.facade.publishGem(this.context, upload))
          .isSameAs(failure)
          .hasSuppressedException(cleanupFailure);
    }
  }
}
