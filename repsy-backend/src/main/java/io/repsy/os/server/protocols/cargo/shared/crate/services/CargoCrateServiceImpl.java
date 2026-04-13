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
package io.repsy.os.server.protocols.cargo.shared.crate.services;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoAuthor;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCategory;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateMeta;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoKeyword;
import io.repsy.os.server.protocols.cargo.shared.crate.mappers.CargoCrateConverter;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoAuthorRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCategoryRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateIndexRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateMetaRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoKeywordRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.crate.services.SemverComparator;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class CargoCrateServiceImpl implements CargoCrateService<UUID> {

  private static final String ERR_REPO_NOT_FOUND = "repoNotFound";
  private static final String ERR_CRATE_NOT_FOUND = "crateNotFound";
  private static final String ERR_CRATE_VERSION_NOT_FOUND = "crateVersionNotFound";

  private final RepoRepository repoRepository;
  private final CargoCrateRepository crateRepository;
  private final CargoCrateIndexRepository crateIndexRepository;
  private final CargoCrateMetaRepository crateMetaRepository;
  private final CargoAuthorRepository authorRepository;
  private final CargoKeywordRepository keywordRepository;
  private final CargoCategoryRepository categoryRepository;
  private final CargoCrateConverter crateConverter;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public void publish(final BaseRepoInfo<UUID> repoInfo, final CratePublishRequest request) {

    final var repo = this.findRepoById(repoInfo.getId());
    final var normalizedName = CrateUtils.normalizeCrateName(request.name());

    final var existingCrate =
        this.crateRepository.findByRepoIdAndName(repoInfo.getId(), normalizedName);

    final CargoCrate crate;

    if (existingCrate.isPresent()) {
      crate = existingCrate.get();
      this.checkExistsVersion(crate, request);
      crate.setLastUpdatedAt(Instant.now());
    } else {
      crate = this.createCrate(repo, request, normalizedName);
    }

    this.updateCrateMaxVersion(crate, request.vers());
    this.syncAuthors(crate, request.authors());
    this.syncKeywords(crate, request.keywords());
    this.syncCategories(crate, request.categories());

    this.crateRepository.save(crate);

    this.createCrateIndex(crate, request);
    this.createCrateMeta(crate, request);
  }

  @Override
  @Transactional
  public void yank(final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    index.setYanked(true);

    this.crateIndexRepository.save(index);
  }

  @Override
  @Transactional
  public void unyank(final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    index.setYanked(false);
    this.crateIndexRepository.save(index);
  }

  @Override
  @Transactional
  public void deleteCrate(final BaseRepoInfo<UUID> repoInfo, final String name) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    this.crateRepository.delete(crate);
  }

  @Override
  @Transactional
  public void deleteCrateVersion(
      final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    final var meta = this.findCrateMeta(crate.getId(), vers);

    this.crateIndexRepository.delete(index);
    this.crateMetaRepository.delete(meta);

    this.recalculateMaxVersion(crate);
  }

  @Override
  public List<CrateIndexEntry> getIndexEntries(
      final BaseRepoInfo<UUID> repoInfo, final String name) {

    final var normalizedName = CrateUtils.normalizeCrateName(name);

    final var entries =
        this.crateIndexRepository.findAllByCrateRepoIdAndName(repoInfo.getId(), normalizedName);

    return entries.stream().map(this.crateConverter::toCrateIndexEntry).toList();
  }

  @Override
  public BaseCrateInfo<UUID> getCrate(final BaseRepoInfo<UUID> repoInfo, final String name) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    return this.crateConverter.toCrateInfo(crate);
  }

  @Override
  public BaseCrateVersionInfo<UUID> getCrateVersion(
      final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    final var index = this.findCrateIndex(crate.getId(), vers);
    final var meta = this.findCrateMeta(crate.getId(), vers);

    return this.crateConverter.toCrateVersionInfo(crate, meta, index);
  }

  @Override
  public Page<CrateListItem> search(
      final BaseRepoInfo<UUID> repoInfo, final String query, final Pageable pageable) {

    return this.crateRepository.findAllByRepoIdAndNameContaining(repoInfo.getId(), query, pageable);
  }

  @Transactional
  @Override
  public void incrementDownloadCount(
      final BaseRepoInfo<UUID> repoInfo, final String crateName, final String version) {

    final var crate = this.findCrate(repoInfo.getId(), crateName);
    crate.setTotalDownloads(crate.getTotalDownloads() + 1);
    this.crateRepository.save(crate);

    this.crateMetaRepository.incrementDownloadCount(crate.getId(), version);
  }

  public Page<CrateVersionListItem> getCrateVersions(
      final BaseRepoInfo<UUID> repoInfo,
      final String name,
      final String query,
      final Pageable pageable) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    final var versions = this.crateMetaRepository.findAllByCrateId(crate.getId());
    final var normalizedQuery = query.toLowerCase();

    final var sortedAndFiltered =
        versions.stream()
            .filter(
                item ->
                    normalizedQuery.isBlank()
                        || item.getVersion().toLowerCase().contains(normalizedQuery))
            .map(item -> new CrateVersionListItem(item.getVersion(), item.getCreatedAt()))
            .sorted(CrateUtils.resolveVersionSort(pageable))
            .toList();

    final var start = (int) pageable.getOffset();
    final var end = Math.min(start + pageable.getPageSize(), sortedAndFiltered.size());
    final var pageContent =
        start > sortedAndFiltered.size()
            ? List.<CrateVersionListItem>of()
            : sortedAndFiltered.subList(start, end);

    return new PageImpl<>(pageContent, pageable, sortedAndFiltered.size());
  }

  private void checkExistsVersion(
      final CargoCrate existingCrate, final CratePublishRequest request) {

    final var versionExists =
        this.crateIndexRepository
            .findByCrateIdAndVers(existingCrate.getId(), request.vers())
            .isPresent();

    if (versionExists) {
      throw new ItemAlreadyExistException(
          "crate `%s@%s` already exists in this registry"
              .formatted(request.name(), request.vers()));
    }
  }

  private CargoCrate createCrate(
      final Repo repo, final CratePublishRequest request, final String normalizedName) {

    final var crate = new CargoCrate();

    crate.setRepo(repo);
    crate.setName(normalizedName);
    crate.setOriginalName(request.name());
    crate.setMaxVersion(request.vers());
    crate.setTotalDownloads(0L);
    crate.setDescription(request.description());
    crate.setHomepage(request.homepage());
    crate.setRepository(request.repository());
    crate.setCreatedAt(Instant.now());
    crate.setLastUpdatedAt(Instant.now());

    return this.crateRepository.save(crate);
  }

  private void createCrateIndex(final CargoCrate crate, final CratePublishRequest request) {

    final var index = new CargoCrateIndex();

    index.setCrate(crate);
    index.setName(crate.getName());
    index.setVers(request.vers());
    index.setDeps(this.toJson(this.mapDeps(request.deps())));
    index.setCksum(request.cksum());
    index.setFeatures(this.toJson(request.features()));
    index.setFeatures2(this.toJson(request.features2()));
    index.setYanked(false);
    index.setLinks(request.links());
    index.setV(request.features2() != null ? 2 : 1);
    index.setRustVersion(request.rustVersion());

    this.crateIndexRepository.save(index);
  }

  private @Nullable List<CrateIndexDep> mapDeps(final @Nullable List<CratePublishDep> deps) {

    if (deps == null) {
      return null;
    }

    return deps.stream().map(this::toIndexDep).toList();
  }

  private CrateIndexDep toIndexDep(final CratePublishDep dep) {

    final boolean hasAlias = dep.explicitNameInToml() != null;

    return new CrateIndexDep(
        hasAlias ? dep.explicitNameInToml() : dep.name(),
        dep.versionReq(),
        dep.features(),
        dep.optional(),
        dep.defaultFeatures(),
        dep.target(),
        dep.kind(),
        dep.registry(),
        hasAlias ? dep.name() : null);
  }

  private void createCrateMeta(final CargoCrate crate, final CratePublishRequest request) {

    final var meta = new CargoCrateMeta();

    meta.setCrate(crate);
    meta.setVersion(request.vers());
    meta.setReadme(request.readme());
    meta.setLicense(request.license());
    meta.setLicenseFile(request.licenseFile());
    meta.setDocumentation(request.documentation());
    meta.setRustVersion(request.rustVersion());
    meta.setDownloads(0L);
    meta.setCreatedAt(Instant.now());

    this.crateMetaRepository.save(meta);
  }

  private void syncAuthors(final CargoCrate crate, final @Nullable List<String> authorStrings) {

    if (authorStrings == null) {
      return;
    }

    crate.getAuthors().clear();

    for (final var authorStr : authorStrings) {
      final var author =
          this.authorRepository
              .findByAuthor(authorStr)
              .orElseGet(() -> this.createAuthor(authorStr));

      crate.getAuthors().add(author);
    }
  }

  private CargoAuthor createAuthor(final String authorStr) {

    final var newAuthor = new CargoAuthor();
    newAuthor.setAuthor(authorStr);
    return this.authorRepository.save(newAuthor);
  }

  private CargoKeyword createKeyword(final String keywordStr) {

    final var newKeyword = new CargoKeyword();
    newKeyword.setKeyword(keywordStr);

    return this.keywordRepository.save(newKeyword);
  }

  private CargoCategory crateCategory(final String categoryStr) {

    final var newCategory = new CargoCategory();
    newCategory.setCategory(categoryStr);

    return this.categoryRepository.save(newCategory);
  }

  private void syncKeywords(final CargoCrate crate, final @Nullable List<String> keywordStrings) {

    if (keywordStrings == null) {
      return;
    }

    crate.getKeywords().clear();

    for (final var keywordStr : keywordStrings) {
      final var keyword =
          this.keywordRepository
              .findByKeyword(keywordStr)
              .orElseGet(() -> this.createKeyword(keywordStr));

      crate.getKeywords().add(keyword);
    }
  }

  private void syncCategories(
      final CargoCrate crate, final @Nullable List<String> categoryStrings) {

    if (categoryStrings == null) {
      return;
    }

    crate.getCategories().clear();

    for (final var categoryStr : categoryStrings) {
      final var category =
          this.categoryRepository
              .findByCategory(categoryStr)
              .orElseGet(() -> this.crateCategory(categoryStr));

      crate.getCategories().add(category);
    }
  }

  private void updateCrateMaxVersion(final CargoCrate crate, final String newVers) {

    final var currentMax = crate.getMaxVersion();
    if (currentMax == null || new SemverComparator().compare(newVers, currentMax) > 0) {
      crate.setMaxVersion(newVers);
    }
    crate.setLastUpdatedAt(Instant.now());
  }

  private void recalculateMaxVersion(final CargoCrate crate) {

    final var remaining = this.crateIndexRepository.findAllByCrateId(crate.getId());

    if (remaining.isEmpty()) {
      this.crateRepository.delete(crate);
      return;
    }

    final var maxVers =
        remaining.stream()
            .map(CargoCrateIndex::getVers)
            .max(new io.repsy.protocols.cargo.shared.crate.services.SemverComparator())
            .orElse("");

    crate.setMaxVersion(maxVers);
    this.crateRepository.save(crate);
  }

  private CargoCrate findCrate(final UUID repoId, final String name) {

    final var normalizedName = CrateUtils.normalizeCrateName(name);

    return this.crateRepository
        .findByRepoIdAndName(repoId, normalizedName)
        .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_NOT_FOUND));
  }

  private CargoCrateIndex findCrateIndex(final UUID repoId, final String name, final String vers) {

    final var crate = this.findCrate(repoId, name);

    return this.crateIndexRepository
        .findByCrateIdAndVers(crate.getId(), vers)
        .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));
  }

  private @Nullable String toJson(final @Nullable Object value) {

    if (value == null) {
      return null;
    }

    try {
      return this.objectMapper.writeValueAsString(value);
    } catch (final JacksonIOException e) {
      log.warn("Failed to serialize value to JSON", e);
      return null;
    }
  }

  private CargoCrateMeta findCrateMeta(final UUID crateId, final String vers) {

    return this.crateMetaRepository
        .findByCrateIdAndVersion(crateId, vers)
        .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));
  }

  private CargoCrateIndex findCrateIndex(final UUID crateId, final String vers) {

    return this.crateIndexRepository
        .findByCrateIdAndVers(crateId, vers)
        .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));
  }

  private Repo findRepoById(final UUID repoId) {

    return this.repoRepository
        .findById(repoId)
        .orElseThrow(() -> new ItemNotFoundException(ERR_REPO_NOT_FOUND));
  }
}
