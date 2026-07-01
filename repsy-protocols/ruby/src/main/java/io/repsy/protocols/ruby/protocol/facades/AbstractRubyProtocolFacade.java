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
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.ruby.shared.utils.GemspecParser;
import io.repsy.protocols.ruby.shared.utils.RubyGemspecMarshalWriter;
import io.repsy.protocols.ruby.shared.utils.RubySpecsIndexWriter;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractRubyProtocolFacade<ID> implements RubyProtocolFacade {

  private static final String USAGES = "usages";
  private static final String GEM_NAME = "gemName";
  private static final String GEM_VERSION = "gemVersion";

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
  public void publishGem(final ProtocolContext context, final byte[] gemBytes) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var metadata = GemspecParser.parse(gemBytes);
    final var checksum = CompactIndexFormatter.sha256Hex(gemBytes);

    final var usages =
        this.storageService.writeGem(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            metadata.getName(),
            metadata.getVersion(),
            metadata.getPlatform(),
            gemBytes);

    this.gemService.publishGem(repoInfo, metadata, checksum);
    this.refreshVersionsChecksum(repoInfo, metadata.getName());

    context.addProperty(USAGES, usages);
    context.addProperty(GEM_NAME, metadata.getName());
    context.addProperty(GEM_VERSION, metadata.getVersion());
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

  public BaseUsages deleteRepo(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var freed = this.storageService.deleteRepo(repoInfo.getStorageKey());
    return BaseUsages.ofDisk(-1L * freed);
  }
}
