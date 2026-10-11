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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.web.paging.VersionSortPaging;
import io.repsy.core.web.utils.LikePatterns;
import io.repsy.core.web_error.ConstraintViolations;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.GemListItem;
import io.repsy.os.generated.model.GemPackageInfo;
import io.repsy.os.generated.model.GemVersionInfo;
import io.repsy.os.generated.model.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemNameProjection;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionCompactItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemDependency;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.mappers.RubyGemMapper;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemDependencyRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.gem.dtos.GemVersionsEntry;
import io.repsy.protocols.ruby.shared.gem.services.RubyGemProtocolService;
import io.repsy.protocols.ruby.shared.storage.services.AbstractRubyStorageService;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.ruby.shared.utils.GemFilenameCandidates;
import io.repsy.protocols.ruby.shared.utils.RubyGemVersionComparator;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RubyGemService implements RubyGemProtocolService<UUID> {

  private static final String RUNTIME_TYPE = "runtime";
  private static final String VERSION_UNIQUE_CONSTRAINT =
      "ux_ruby_gem_version__gem_id_version_platform";

  private final RubyGemRepository gemRepository;
  private final RubyGemVersionRepository versionRepository;
  private final RubyGemDependencyRepository dependencyRepository;
  private final RepoTxService repoTxService;
  private final RubyGemMapper converter;

  @Override
  public List<String> getGemNames(final BaseRepoInfo<UUID> repoInfo) {
    return this.gemRepository.findAllNamesByRepoId(repoInfo.getId()).stream()
        .map(GemNameProjection::getName)
        .toList();
  }

  @Override
  public List<GemCompactEntry> getCompactEntriesByGemName(
      final BaseRepoInfo<UUID> repoInfo, final String gemName) {
    final var gem =
        this.gemRepository
            .findByRepoIdAndName(repoInfo.getId(), gemName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));
    final var rows = this.versionRepository.findAllCompactByGemId(gem.getId());
    return this.toCompactEntries(rows);
  }

  @Override
  public List<GemCompactEntry> getCompactEntriesByGemNames(
      final BaseRepoInfo<UUID> repoInfo, final List<String> gemNames) {
    if (gemNames.isEmpty()) {
      return List.of();
    }
    final var rows =
        this.versionRepository.findAllNonYankedCompactByRepoIdAndGemNameIn(
            repoInfo.getId(), gemNames);
    return this.toCompactEntries(rows);
  }

  @Override
  public Optional<GemCompactEntry> findByGemFilename(
      final BaseRepoInfo<UUID> repoInfo, final String filename) {
    for (final var candidate : GemFilenameCandidates.split(filename)) {
      final var match =
          this.gemRepository
              .findByRepoIdAndName(repoInfo.getId(), candidate.name())
              .flatMap(gem -> this.findMatchingVersion(gem.getId(), filename));
      if (match.isPresent()) {
        return match;
      }
    }
    return Optional.empty();
  }

  private Optional<GemCompactEntry> findMatchingVersion(final UUID gemId, final String filename) {
    return this.versionRepository.findAllCompactByGemId(gemId).stream()
        .filter(
            row ->
                filename.equals(
                    AbstractRubyStorageService.buildFilename(
                        row.getGemName(), row.getVersion(), row.getPlatform())))
        .findFirst()
        .map(this::toSpecsEntryWithDependencies);
  }

  /**
   * Like {@link #toSpecsEntry}, plus the row's own runtime dependencies (RPS-1554): {@link
   * #findByGemFilename} resolves at most one row, so one extra query here is not the N+1 that
   * batching in {@link #toCompactEntries} avoids for a whole gem's or repo's worth of rows. {@code
   * getGemspec} (the one caller that needs the dependencies) reaches this through {@link
   * #findByGemFilename}; the other callers (a download, an existence check) pay for and discard
   * them, which is cheaper than a second, dependency-aware resolution path.
   */
  private GemCompactEntry toSpecsEntryWithDependencies(final GemVersionCompactItem row) {
    final var runtimeDeps =
        this.dependencyRepository.findAllByGemVersionId(row.getGemVersionId()).stream()
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

  @Override
  public boolean gemNameExists(final BaseRepoInfo<UUID> repoInfo, final String gemName) {
    return this.gemRepository.existsByRepoIdAndName(repoInfo.getId(), gemName);
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public BaseUsages publishGem(
      final BaseRepoInfo<UUID> repoInfo,
      final GemMetadata metadata,
      final String checksum,
      final GemFileWriter fileWriter)
      throws IOException {
    final boolean replacesExisting;

    try {
      final var repo = this.requireRepo(repoInfo.getId());
      final var gem = this.upsertGem(repo, metadata.getName(), metadata.getVersion());
      replacesExisting = this.upsertVersion(gem, metadata, checksum, repoInfo.isAllowOverride());
      // Flush so a unique-index conflict (a concurrent push of the same version) fails here, before
      // the file is written. The transaction, and the row lock it holds, stays open while the file
      // is written, so a losing push waits for the winner instead of replacing its file.
      this.versionRepository.flush();
    } catch (final DataIntegrityViolationException e) {
      // Only that index means the version exists. Any other violation is not the client's
      // conflict, so it is left to surface as the server error it is.
      if (!ConstraintViolations.violatesConstraint(e, VERSION_UNIQUE_CONSTRAINT)) {
        throw e;
      }

      throw new ItemAlreadyExistException(ProtocolErrorCodes.GEM_VERSION_ALREADY_EXISTS);
    }

    return fileWriter.write(replacesExisting);
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
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));

    final var gemVersion =
        this.versionRepository
            .findByGemIdAndVersionAndPlatform(gem.getId(), version, platform)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_VERSION_NOT_FOUND));

    if (gemVersion.isYanked()) {
      throw new BadRequestException(ProtocolErrorCodes.GEM_VERSION_ALREADY_YANKED);
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
        .findAllByRepoIdContainsName(repoId, LikePatterns.of("%", name, "%"), pageable)
        .map(this.converter::toGemListItemDto);
  }

  public Page<GemVersionListItem> findAllVersions(
      final UUID gemId, final String version, final Pageable pageable) {

    final var versionOrder = VersionSortPaging.directionFor(pageable, "version");

    if (versionOrder != null) {
      final var versions =
          this.versionRepository
              .findAllByGemId(gemId, LikePatterns.of("%", version, "%"), Pageable.unpaged())
              .getContent();

      return VersionSortPaging.sortAndPage(
          versions.stream().map(this.converter::toGemVersionListItemDto).toList(),
          pageable,
          versionOrder,
          Comparator.comparing(GemVersionListItem::getVersion, new RubyGemVersionComparator()));
    }

    return this.versionRepository
        .findAllByGemId(gemId, LikePatterns.of("%", version, "%"), pageable)
        .map(this.converter::toGemVersionListItemDto);
  }

  public GemVersionInfo getVersionInfo(
      final UUID gemId, final String version, final String platform) {
    final var gemVersion =
        this.versionRepository
            .findByGemIdAndVersionAndPlatform(gemId, version, platform)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_VERSION_NOT_FOUND));
    final var deps = this.dependencyRepository.findAllByGemVersionId(gemVersion.getId());
    return this.converter.toGemVersionInfoDto(gemVersion, deps);
  }

  /**
   * The package summary: the gem's name and latest version, described by the newest row of that
   * version (platform variants of one version share the number), and when that version was last
   * published.
   *
   * @throws ItemNotFoundException when the repo has no gem of that name
   */
  public GemPackageInfo getPackageInfo(final UUID repoId, final String gemName) {
    final var gem =
        this.gemRepository
            .findByRepoIdAndName(repoId, gemName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));

    final var latestRows =
        this.versionRepository.findByGemIdAndVersion(gem.getId(), gem.getLatest());
    final var newest =
        latestRows.stream()
            .max(Comparator.comparing(RubyGemVersion::getCreatedAt))
            .or(() -> this.versionRepository.findFirstByGemIdOrderByCreatedAtDesc(gem.getId()))
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));

    return this.converter.toGemPackageInfoDto(gem, newest);
  }

  public UUID getGemId(final UUID repoId, final String gemName) {
    return this.gemRepository
        .findByRepoIdAndName(repoId, gemName)
        .map(RubyGem::getId)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));
  }

  @Transactional
  public void deleteGem(final UUID gemId) {
    this.gemRepository.deleteById(gemId);
  }

  /**
   * Deletes one version row, yanked or not, and keeps the gem's own bookkeeping true for the
   * versions that are left: {@code latest} moves off a version that no longer exists, and the
   * versions checksum that {@code /versions} serves follows the new {@code /info} (RPS-1426).
   *
   * @return how many versions of the gem remain, so the caller can remove an emptied gem
   * @throws ItemNotFoundException when the gem has no such version and platform
   */
  @Transactional
  public long deleteVersion(final UUID gemId, final String version, final String platform) {
    final var gemVersion =
        this.versionRepository
            .findByGemIdAndVersionAndPlatform(gemId, version, platform)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_VERSION_NOT_FOUND));
    this.versionRepository.delete(gemVersion);
    this.versionRepository.flush();

    final var remaining = this.versionRepository.countByGemId(gemId);
    if (remaining > 0) {
      this.refreshGemAfterVersionDelete(gemId);
    }
    return remaining;
  }

  private void refreshGemAfterVersionDelete(final UUID gemId) {
    final var gem =
        this.gemRepository
            .findById(gemId)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));

    if (!this.versionRepository.existsByGemIdAndVersion(gemId, gem.getLatest())) {
      // Same choice as yankGem: the newest live version, else the newest one there is.
      final var newLatest =
          this.versionRepository
              .findFirstByGemIdAndYankedFalseOrderByCreatedAtDesc(gemId)
              .or(() -> this.versionRepository.findFirstByGemIdOrderByCreatedAtDesc(gemId));
      newLatest.ifPresent(v -> gem.setLatest(v.getVersion()));
    }

    final var entries = this.toCompactEntries(this.versionRepository.findAllCompactByGemId(gemId));
    gem.setVersionsChecksum(
        CompactIndexFormatter.md5Hex(CompactIndexFormatter.formatGemInfo(entries)));
    this.gemRepository.save(gem);
  }

  @Override
  public Map<String, GemVersionsEntry> getVersionsChecksums(final BaseRepoInfo<UUID> repoInfo) {
    final var versionsCsvByGem =
        this.versionRepository.findAllCompactByRepoId(repoInfo.getId()).stream()
            .collect(
                Collectors.groupingBy(
                    GemVersionCompactItem::getGemName,
                    Collectors.mapping(
                        v ->
                            CompactIndexFormatter.formatVersionEntry(
                                v.getVersion(), v.getPlatform(), v.isYanked()),
                        Collectors.joining(","))));
    return this.gemRepository.findAllByRepoId(repoInfo.getId()).stream()
        .filter(g -> g.getVersionsChecksum() != null)
        .collect(
            Collectors.toMap(
                RubyGem::getName,
                g ->
                    new GemVersionsEntry(
                        versionsCsvByGem.getOrDefault(g.getName(), ""),
                        Objects.requireNonNull(g.getVersionsChecksum()))));
  }

  @Override
  @Transactional
  public void saveVersionsChecksum(
      final BaseRepoInfo<UUID> repoInfo, final String gemName, final String checksum) {
    this.gemRepository.updateVersionsChecksum(repoInfo.getId(), gemName, checksum);
  }

  @Override
  public List<GemCompactEntry> getAllNonYankedEntries(final BaseRepoInfo<UUID> repoInfo) {
    return this.versionRepository.findAllNonYankedCompactByRepoId(repoInfo.getId()).stream()
        .map(this::toSpecsEntry)
        .toList();
  }

  private GemCompactEntry toSpecsEntry(final GemVersionCompactItem row) {
    return GemCompactEntry.builder()
        .gemName(row.getGemName())
        .version(row.getVersion())
        .platform(row.getPlatform())
        .checksum(row.getChecksum())
        .yanked(row.isYanked())
        .createdAt(row.getCreatedAt())
        .runtimeDependencies(List.of())
        .build();
  }

  private List<GemCompactEntry> toCompactEntries(final List<GemVersionCompactItem> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    final var versionIds = rows.stream().map(GemVersionCompactItem::getGemVersionId).toList();
    final var depsByVersionId =
        this.dependencyRepository.findAllByGemVersionIdIn(versionIds).stream()
            .collect(Collectors.groupingBy(d -> d.getGemVersion().getId()));
    return rows.stream().map(row -> this.toCompactEntry(row, depsByVersionId)).toList();
  }

  private GemCompactEntry toCompactEntry(
      final GemVersionCompactItem row, final Map<UUID, List<RubyGemDependency>> depsByVersionId) {
    final var deps = depsByVersionId.getOrDefault(row.getGemVersionId(), List.of());
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
    return this.repoTxService.requireRepo(repoId);
  }

  private RubyGem upsertGem(final Repo repo, final String name, final String version) {
    final var gem = this.findOrCreateGem(repo, name, version);
    gem.setLatest(version);
    return this.gemRepository.save(gem);
  }

  /**
   * Returns the gem row, inserting it when this is the first version of a gem name.
   *
   * <p>The insert skips a row that already exists instead of failing on the unique index: on
   * PostgreSQL a failed statement aborts the transaction, which now also holds the version row and
   * the file write. When a concurrent first push has inserted the gem but not committed yet, the
   * statement waits for it, and then finds the committed row.
   */
  private RubyGem findOrCreateGem(final Repo repo, final String name, final String version) {
    final var existing = this.gemRepository.findByRepoIdAndName(repo.getId(), name);

    if (existing.isPresent()) {
      return existing.get();
    }

    this.gemRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repo.getId(), name, version, Instant.now());

    return this.gemRepository
        .findByRepoIdAndName(repo.getId(), name)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND));
  }

  /**
   * @return whether the version already had a row, which is being replaced
   */
  private boolean upsertVersion(
      final RubyGem gem,
      final GemMetadata metadata,
      final String checksum,
      final boolean allowOverride) {
    final var existing =
        this.versionRepository.findByGemIdAndVersionAndPlatform(
            gem.getId(), metadata.getVersion(), metadata.getPlatform());

    if (existing.isPresent()) {
      final var v = existing.get();
      if (v.isYanked() || !allowOverride) {
        throw new ItemAlreadyExistException(ProtocolErrorCodes.GEM_VERSION_ALREADY_EXISTS);
      }
      this.overrideVersion(v, metadata, checksum);
      return true;
    }

    this.createVersion(gem, metadata, checksum);
    return false;
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
