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

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.ruby.shared.utils.GemspecParser;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
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
  public String getGemInfo(final ProtocolContext context, final String gemName) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var entries = this.gemService.getCompactEntriesByGemName(repoInfo, gemName);
    return CompactIndexFormatter.formatGemInfo(entries);
  }

  @Override
  public Resource downloadGem(final ProtocolContext context, final String filename) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.storageService.getGem(repoInfo.getStorageKey(), repoInfo.getName(), filename);
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
