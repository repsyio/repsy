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
package io.repsy.os.server.protocols.ruby.ui.facades;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.GemListItem;
import io.repsy.os.generated.model.GemVersionInfo;
import io.repsy.os.generated.model.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.services.RubyGemServiceImpl;
import io.repsy.os.server.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@NullMarked
public class RubyApiFacade implements ProtocolApiFacade {

  private final RubyGemServiceImpl gemService;
  private final RubyStorageService storageService;

  @Override
  public BaseUsages deleteRepo(final RepoInfo repoInfo) {
    final var freed = this.storageService.deleteRepo(repoInfo.getStorageKey());
    return BaseUsages.ofDisk(-1L * freed);
  }

  public Page<GemListItem> listGems(
      final RepoInfo repoInfo, final String name, final Pageable pageable) {
    return this.gemService.findAllGems(repoInfo.getStorageKey(), name, pageable);
  }

  public Page<GemVersionListItem> listVersions(
      final RepoInfo repoInfo,
      final String gemName,
      final String version,
      final Pageable pageable) {
    final var gemId = this.gemService.getGemId(repoInfo.getStorageKey(), gemName);
    return this.gemService.findAllVersions(gemId, version, pageable);
  }

  public GemVersionInfo getVersionInfo(
      final RepoInfo repoInfo, final String gemName, final String version, final String platform) {
    final var gemId = this.gemService.getGemId(repoInfo.getStorageKey(), gemName);
    return this.gemService.getVersionInfo(gemId, version, platform);
  }

  @Transactional
  public BaseUsages deleteGem(final RepoInfo repoInfo, final String gemName) {
    final var gemId = this.gemService.getGemId(repoInfo.getStorageKey(), gemName);
    final var freed =
        this.storageService.deleteAllGems(repoInfo.getStorageKey(), repoInfo.getName(), gemName);
    this.gemService.deleteGem(gemId);
    return BaseUsages.ofDisk(-1L * freed);
  }

  @Transactional
  public BaseUsages deleteGemVersion(
      final RepoInfo repoInfo, final String gemName, final String version, final String platform) {
    final var gemId = this.gemService.getGemId(repoInfo.getStorageKey(), gemName);

    if (this.gemService.countNonYankedVersions(gemId) <= 1) {
      return this.deleteGem(repoInfo, gemName);
    }

    final var freed =
        this.storageService.deleteGem(
            repoInfo.getStorageKey(), repoInfo.getName(), gemName, version, platform);
    this.gemService.deleteVersion(gemId, version, platform);
    return BaseUsages.ofDisk(-1L * freed);
  }

  public void createRepo(final UUID repoId) {
    this.storageService.createRepo(repoId);
  }
}
