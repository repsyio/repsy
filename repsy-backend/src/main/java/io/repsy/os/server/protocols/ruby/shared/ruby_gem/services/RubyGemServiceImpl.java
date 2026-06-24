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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.GemListItem;
import io.repsy.os.generated.model.GemVersionInfo;
import io.repsy.os.generated.model.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemNameProjection;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionCompactItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemDependency;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.mappers.RubyGemConverter;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemDependencyRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class RubyGemServiceImpl implements RubyGemProtocolService<UUID> {

  private static final String GEM_NOT_FOUND = "gemNotFound";
  private static final String GEM_VERSION_NOT_FOUND = "gemVersionNotFound";
  private static final String REPO_NOT_FOUND = "repoNotFound";
  private static final String RUNTIME_TYPE = "runtime";

  private final RubyGemRepository gemRepository;
  private final RubyGemVersionRepository versionRepository;
  private final RubyGemDependencyRepository dependencyRepository;
  private final RepoRepository repoRepository;
  private final RubyGemConverter converter;

  @Override
  public List<String> getGemNames(final BaseRepoInfo<UUID> repoInfo) {
    return this.gemRepository.findAllNamesByRepoId(repoInfo.getId()).stream()
        .map(GemNameProjection::getName)
        .toList();
  }

  @Override
  public List<GemCompactEntry> getAllCompactEntries(final BaseRepoInfo<UUID> repoInfo) {
    final var rows = this.versionRepository.findAllCompactByRepoId(repoInfo.getId());
    return this.toCompactEntries(rows);
  }

  @Override
  public List<GemCompactEntry> getCompactEntriesByGemName(
      final BaseRepoInfo<UUID> repoInfo, final String gemName) {
    final var gem =
        this.gemRepository
            .findByRepoIdAndName(repoInfo.getId(), gemName)
            .orElseThrow(() -> new ItemNotFoundException(GEM_NOT_FOUND));
    final var rows = this.versionRepository.findAllCompactByGemId(gem.getId());
    return this.toCompactEntries(rows);
  }

  @Override
  @Transactional
  public void publishGem(
      final BaseRepoInfo<UUID> repoInfo, final GemMetadata metadata, final String checksum) {
    final var repo = this.requireRepo(repoInfo.getId());
    final var gem = this.upsertGem(repo, metadata.getName(), metadata.getVersion());
    this.upsertVersion(gem, metadata, checksum, repoInfo.isAllowOverride());
  }

  @Override
  @Transactional
  public void yankGem(
      final BaseRepoInfo<UUID> repoInfo,
      final String gemName,
      final String version,
      final String platform) {
    final var gem =
        this.gemRepository
            .findByRepoIdAndName(repoInfo.getId(), gemName)
            .orElseThrow(() -> new ItemNotFoundException(GEM_NOT_FOUND));

    final var gemVersion =
        this.versionRepository
            .findByGemIdAndVersionAndPlatform(gem.getId(), version, platform)
            .orElseThrow(() -> new ItemNotFoundException(GEM_VERSION_NOT_FOUND));

    if (gemVersion.isYanked()) {
      throw new BadRequestException("gemVersionAlreadyYanked");
    }

    gemVersion.setYanked(true);
    this.versionRepository.save(gemVersion);

    if (gem.getLatest().equals(version)) {
      this.versionRepository
          .findFirstByGemIdAndYankedFalseOrderByCreatedAtDesc(gem.getId())
          .ifPresent(
              v -> {
                gem.setLatest(v.getVersion());
                this.gemRepository.save(gem);
              });
    }
  }

  public Page<GemListItem> findAllGems(
      final UUID repoId, final String name, final Pageable pageable) {
    return this.gemRepository
        .findAllByRepoIdContainsName(repoId, name, pageable)
        .map(this.converter::toGemListItemDto);
  }

  public Page<GemVersionListItem> findAllVersions(
      final UUID gemId, final String version, final Pageable pageable) {
    return this.versionRepository
        .findAllByGemId(gemId, version, pageable)
        .map(this.converter::toGemVersionListItemDto);
  }

  public GemVersionInfo getVersionInfo(
      final UUID gemId, final String version, final String platform) {
    final var gemVersion =
        this.versionRepository
            .findByGemIdAndVersionAndPlatform(gemId, version, platform)
            .orElseThrow(() -> new ItemNotFoundException(GEM_VERSION_NOT_FOUND));
    final var deps = this.dependencyRepository.findAllByGemVersionId(gemVersion.getId());
    return this.converter.toGemVersionInfoDto(gemVersion, deps);
  }

  public UUID getGemId(final UUID repoId, final String gemName) {
    return this.gemRepository
        .findByRepoIdAndName(repoId, gemName)
        .map(RubyGem::getId)
        .orElseThrow(() -> new ItemNotFoundException(GEM_NOT_FOUND));
  }

  @Transactional
  public void deleteGem(final UUID gemId) {
    this.gemRepository.deleteById(gemId);
  }

  @Transactional
  public void deleteVersion(final UUID gemId, final String version, final String platform) {
    this.versionRepository
        .findByGemIdAndVersionAndPlatform(gemId, version, platform)
        .ifPresent(v -> this.versionRepository.deleteById(v.getId()));
  }

  public long countNonYankedVersions(final UUID gemId) {
    return this.versionRepository.countByGemIdAndYankedFalse(gemId);
  }

  @Override
  public Map<String, String> getVersionsChecksums(final BaseRepoInfo<UUID> repoInfo) {
    return this.gemRepository.findAllByRepoId(repoInfo.getId()).stream()
        .filter(g -> g.getVersionsChecksum() != null)
        .collect(Collectors.toMap(RubyGem::getName, RubyGem::getVersionsChecksum));
  }

  @Override
  @Transactional
  public void saveVersionsChecksum(
      final BaseRepoInfo<UUID> repoInfo, final String gemName, final String checksum) {
    this.gemRepository.updateVersionsChecksum(repoInfo.getId(), gemName, checksum);
  }

  private List<GemCompactEntry> toCompactEntries(final List<GemVersionCompactItem> rows) {
    return rows.stream().map(this::toCompactEntry).toList();
  }

  private GemCompactEntry toCompactEntry(final GemVersionCompactItem row) {
    final var deps = this.dependencyRepository.findAllByGemVersionId(row.getGemVersionId());
    final var runtimeDeps =
        deps.stream()
            .filter(d -> RUNTIME_TYPE.equals(d.getType()))
            .map(this::toDependencyDto)
            .toList();

    return GemCompactEntry.builder()
        .gemName(row.getGemName())
        .version(row.getVersion())
        .platform(row.getPlatform())
        .checksum(row.getChecksum())
        .yanked(row.isYanked())
        .createdAt(row.getCreatedAt())
        .runtimeDependencies(runtimeDeps)
        .build();
  }

  private GemDependency toDependencyDto(final RubyGemDependency dep) {
    return GemDependency.builder()
        .name(dep.getName())
        .requirements(dep.getRequirements())
        .type(dep.getType())
        .build();
  }

  private Repo requireRepo(final UUID repoId) {
    return this.repoRepository
        .findById(repoId)
        .orElseThrow(() -> new ItemNotFoundException(REPO_NOT_FOUND));
  }

  private RubyGem upsertGem(final Repo repo, final String name, final String version) {
    return this.gemRepository
        .findByRepoIdAndName(repo.getId(), name)
        .map(
            g -> {
              g.setLatest(version);
              return this.gemRepository.save(g);
            })
        .orElseGet(
            () -> {
              final var gem = new RubyGem();
              gem.setRepo(repo);
              gem.setName(name);
              gem.setLatest(version);
              return this.gemRepository.save(gem);
            });
  }

  private void upsertVersion(
      final RubyGem gem,
      final GemMetadata metadata,
      final String checksum,
      final boolean allowOverride) {
    final var existing =
        this.versionRepository.findByGemIdAndVersionAndPlatform(
            gem.getId(), metadata.getVersion(), metadata.getPlatform());

    if (existing.isPresent()) {
      final var v = existing.get();
      if (!v.isYanked() && !allowOverride) {
        throw new ItemAlreadyExistException("gemVersionAlreadyExists");
      }
      this.overrideVersion(v, metadata, checksum);
      return;
    }

    this.createVersion(gem, metadata, checksum);
  }

  private void overrideVersion(
      final RubyGemVersion existing, final GemMetadata metadata, final String checksum) {
    existing.setYanked(false);
    existing.setChecksum(checksum);
    existing.setAuthors(metadata.getAuthors());
    existing.setDescription(metadata.getDescription());
    existing.setHomepage(metadata.getHomepage());
    existing.setRequiredRubyVersion(metadata.getRequiredRubyVersion());
    this.versionRepository.save(existing);

    final var oldDeps = this.dependencyRepository.findAllByGemVersionId(existing.getId());
    this.dependencyRepository.deleteAll(oldDeps);
    this.saveDependencies(existing, metadata);
  }

  private void createVersion(final RubyGem gem, final GemMetadata metadata, final String checksum) {
    final var version = new RubyGemVersion();
    version.setGem(gem);
    version.setVersion(metadata.getVersion());
    version.setPlatform(metadata.getPlatform());
    version.setChecksum(checksum);
    version.setAuthors(metadata.getAuthors());
    version.setDescription(metadata.getDescription());
    version.setHomepage(metadata.getHomepage());
    version.setRequiredRubyVersion(metadata.getRequiredRubyVersion());
    final var saved = this.versionRepository.save(version);
    this.saveDependencies(saved, metadata);
  }

  private void saveDependencies(final RubyGemVersion version, final GemMetadata metadata) {
    metadata.getRuntimeDependencies().stream()
        .map(d -> this.toDependencyEntity(version, d, RUNTIME_TYPE))
        .forEach(this.dependencyRepository::save);

    metadata.getDevelopmentDependencies().stream()
        .map(d -> this.toDependencyEntity(version, d, "development"))
        .forEach(this.dependencyRepository::save);
  }

  private RubyGemDependency toDependencyEntity(
      final RubyGemVersion version,
      final io.repsy.protocols.ruby.shared.gem.dtos.GemDependency dep,
      final String type) {
    final var entity = new RubyGemDependency();
    entity.setGemVersion(version);
    entity.setName(dep.getName());
    entity.setRequirements(dep.getRequirements());
    entity.setType(type);
    return entity;
  }
}
