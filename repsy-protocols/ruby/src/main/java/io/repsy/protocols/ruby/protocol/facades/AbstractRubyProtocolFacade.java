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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.ruby.shared.utils.GemspecParser;
import io.repsy.protocols.ruby.shared.utils.RubyGemspecMarshalWriter;
import io.repsy.protocols.ruby.shared.utils.RubySpecsIndexWriter;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractRubyProtocolFacade<ID> implements RubyProtocolFacade {

  private static final String USAGES = "usages";
  private static final String GEM_NAME = "gemName";
  private static final String GEM_VERSION = "gemVersion";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  private final RubyGemProtocolService<ID> gemService;
  private final RubyStorageService storageService;

  @Override
  public String getNames(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var names = this.gemService.getGemNames(repoInfo);
    return CompactIndexFormatter.formatNames(names);
  }

  @Override
  public String getVersionsIndex(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return CompactIndexFormatter.formatVersionsIndex(
        this.gemService.getVersionsChecksums(repoInfo));
  }

  @Override
  public byte[] getGemspec(final ProtocolContext context, final String name, final String version) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var found =
        this.gemService.getCompactEntriesByGemName(repoInfo, name).stream()
            .filter(e -> !e.isYanked() && version.equals(e.getVersion()))
            .findFirst();
    if (found.isEmpty()) {
      throw new ItemNotFoundException("gemVersionNotFound");
    }
    return RubyGemspecMarshalWriter.dumpGemspec(name, version);
  }

  @Override
  public String getGemInfo(final ProtocolContext context, final String gemName) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var entries = this.gemService.getCompactEntriesByGemName(repoInfo, gemName);
    return CompactIndexFormatter.formatGemInfo(entries);
  }

  @Override
  public Resource downloadGem(final ProtocolContext context, final String filename) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    this.checkNotYanked(repoInfo, filename);
    return this.storageService.getGem(repoInfo.getStorageKey(), repoInfo.getName(), filename);
  }

  private void checkNotYanked(final BaseRepoInfo<ID> repoInfo, final String filename) {
    final var parsed = parseGemFilename(filename);
    if (parsed == null) {
      return;
    }
    final var yanked =
        this.gemService.getCompactEntriesByGemName(repoInfo, parsed[0]).stream()
            .anyMatch(e -> e.isYanked() && parsed[1].equals(e.getVersion()));
    if (yanked) {
      throw new ItemNotFoundException("gemVersionNotFound");
    }
  }

  private static String @Nullable [] parseGemFilename(final String filename) {
    if (!filename.endsWith(".gem")) {
      return null;
    }
    final var base = filename.substring(0, filename.length() - ".gem".length());
    final var boundary = findBoundary(base);
    if (boundary < 0) {
      return null;
    }
    return new String[] {base.substring(0, boundary), parseVersion(base.substring(boundary + 1))};
  }

  private static int findBoundary(final String s) {
    for (var i = 0; i < s.length() - 1; i++) {
      if (s.charAt(i) == '-' && Character.isDigit(s.charAt(i + 1))) {
        return i;
      }
    }
    return -1;
  }

  private static String parseVersion(final String versionAndPlatform) {
    final var lastDash = versionAndPlatform.lastIndexOf('-');
    if (lastDash < 0) {
      return versionAndPlatform;
    }
    final var platformPart = versionAndPlatform.substring(lastDash + 1);
    if (platformPart.isEmpty() || Character.isDigit(platformPart.charAt(0))) {
      return versionAndPlatform;
    }
    return versionAndPlatform.substring(0, lastDash);
  }

  @Override
  public void publishGem(final ProtocolContext context, final SpooledUpload gem)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final GemMetadata metadata;
    try (final var in = gem.openStream()) {
      metadata = GemspecParser.parse(in);
    }

    final var usages =
        this.gemService.publishGem(
            repoInfo,
            metadata,
            gem.sha256Hex(),
            replacesExisting -> this.storeGem(repoInfo, metadata, gem, replacesExisting));

    context.addProperty(USAGES, usages);
    context.addProperty(GEM_NAME, metadata.getName());
    context.addProperty(GEM_VERSION, metadata.getVersion());
    context.addProperty(ARTIFACT_NAME, metadata.getName());
    context.addProperty(ARTIFACT_VERSION, metadata.getVersion());
  }

  /**
   * Refreshes the versions checksum and stores the gem file, in that order, while {@link
   * RubyGemProtocolService#publishGem} still holds the version's row: a failure of either rolls the
   * row back, and for a new version the partly written file is removed.
   */
  private BaseUsages storeGem(
      final BaseRepoInfo<ID> repoInfo,
      final GemMetadata metadata,
      final SpooledUpload gem,
      final boolean replacesExisting)
      throws IOException {

    this.refreshVersionsChecksum(repoInfo, metadata.getName());

    try (final var in = gem.openStream()) {
      return this.storageService.writeGem(
          repoInfo.getStorageKey(),
          repoInfo.getName(),
          metadata.getName(),
          metadata.getVersion(),
          metadata.getPlatform(),
          in);
    } catch (final IOException | RuntimeException e) {
      // The row is rolled back with this failure, so a half-written new version would be an
      // orphaned file. A version being replaced keeps its row, so its file is left alone.
      if (!replacesExisting) {
        this.discardPartialGem(repoInfo, metadata, e);
      }
      throw e;
    }
  }

  private void discardPartialGem(
      final BaseRepoInfo<ID> repoInfo, final GemMetadata metadata, final Exception cause) {

    try {
      this.storageService.deleteGem(
          repoInfo.getStorageKey(),
          repoInfo.getName(),
          metadata.getName(),
          metadata.getVersion(),
          metadata.getPlatform());
    } catch (final RuntimeException e) {
      // Nothing to delete when the failure came before the file was created.
      log.debug(
          "No partial file removed for gem {} {}: {}",
          metadata.getName(),
          metadata.getVersion(),
          e.getMessage());
      cause.addSuppressed(e);
    }
  }

  @Override
  public void yankGem(
      final ProtocolContext context,
      final String gemName,
      final String version,
      final String platform) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    this.gemService.yankGem(repoInfo, gemName, version, platform);
    this.refreshVersionsChecksum(repoInfo, gemName);
  }

  @Override
  public byte[] getSpecs(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return RubySpecsIndexWriter.dumpSpecs(this.gemService.getAllNonYankedEntries(repoInfo));
  }

  @Override
  public byte[] getLatestSpecs(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return RubySpecsIndexWriter.dumpLatestSpecs(this.gemService.getAllNonYankedEntries(repoInfo));
  }

  @Override
  public byte[] getPrereleaseSpecs(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return RubySpecsIndexWriter.dumpPrereleaseSpecs(
        this.gemService.getAllNonYankedEntries(repoInfo));
  }

  private void refreshVersionsChecksum(final BaseRepoInfo<ID> repoInfo, final String gemName) {
    final var entries = this.gemService.getCompactEntriesByGemName(repoInfo, gemName);
    final var checksum = CompactIndexFormatter.md5Hex(CompactIndexFormatter.formatGemInfo(entries));
    this.gemService.saveVersionsChecksum(repoInfo, gemName, checksum);
  }
}
