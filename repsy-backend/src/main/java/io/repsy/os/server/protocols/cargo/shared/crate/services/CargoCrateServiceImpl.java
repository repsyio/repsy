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
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.Comparator;
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

    final var repo =
        this.repoRepository
            .findById(repoInfo.getId())
            .orElseThrow(() -> new ItemNotFoundException(ERR_REPO_NOT_FOUND));

    final var normalizedName = normalizeName(request.name());
    final var existingCrate =
        this.crateRepository.findByRepoIdAndName(repoInfo.getId(), normalizedName);

    if (existingCrate.isPresent()) {
      final var versionExists =
          this.crateIndexRepository
              .findByCrateIdAndVers(existingCrate.get().getId(), request.vers())
              .isPresent();
      if (versionExists) {
        throw new ItemAlreadyExistException(
            "crate `%s@%s` already exists in this registry"
                .formatted(request.name(), request.vers()));
      }
    }

    final CargoCrate crate;

    if (existingCrate.isPresent()) {
      crate = existingCrate.get();
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

    log.info(
        "Crate published: {} {} for repo {}", request.name(), request.vers(), repoInfo.getId());
  }

  @Override
  @Transactional
  public void yank(final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    index.setYanked(true);
    this.crateIndexRepository.save(index);

    log.info("Crate yanked: {} {} for repo {}", name, vers, repoInfo.getId());
  }

  @Override
  @Transactional
  public void unyank(final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    index.setYanked(false);
    this.crateIndexRepository.save(index);

    log.info("Crate unyanked: {} {} for repo {}", name, vers, repoInfo.getId());
  }

  @Override
  @Transactional
  public void deleteCrate(final BaseRepoInfo<UUID> repoInfo, final String name) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    this.crateRepository.delete(crate);

    log.info("Crate deleted: {} for repo {}", name, repoInfo.getId());
  }

  @Override
  @Transactional
  public void deleteCrateVersion(
      final BaseRepoInfo<UUID> repoInfo, final String name, final String vers) {

    final var crate = this.findCrate(repoInfo.getId(), name);
    final var index = this.findCrateIndex(repoInfo.getId(), name, vers);
    final var meta =
        this.crateMetaRepository
            .findByCrateIdAndVersion(crate.getId(), vers)
            .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));

    this.crateIndexRepository.delete(index);
    this.crateMetaRepository.delete(meta);

    this.recalculateMaxVersion(crate);

    log.info("Crate version deleted: {} {} for repo {}", name, vers, repoInfo.getId());
  }

  @Override
  public List<CrateIndexEntry> getIndexEntries(
      final BaseRepoInfo<UUID> repoInfo, final String name) {

    final var normalizedName = normalizeName(name);
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
    final var index =
        this.crateIndexRepository
            .findByCrateIdAndVers(crate.getId(), vers)
            .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));
    final var meta =
        this.crateMetaRepository
            .findByCrateIdAndVersion(crate.getId(), vers)
            .orElseThrow(() -> new ItemNotFoundException(ERR_CRATE_VERSION_NOT_FOUND));

    return this.crateConverter.toCrateVersionInfo(crate, meta, index);
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
            .sorted(resolveVersionSort(pageable))
            .toList();

    final var start = (int) pageable.getOffset();
    final var end = Math.min(start + pageable.getPageSize(), sortedAndFiltered.size());
    final var pageContent =
        start > sortedAndFiltered.size()
            ? List.<CrateVersionListItem>of()
            : sortedAndFiltered.subList(start, end);

    return new PageImpl<>(pageContent, pageable, sortedAndFiltered.size());
  }

  private static Comparator<CrateVersionListItem> resolveVersionSort(final Pageable pageable) {

    if (pageable.getSort().isUnsorted()) {
      return Comparator.comparing(CrateVersionListItem::createdAt).reversed();
    }

    final var order = pageable.getSort().iterator().next();
    Comparator<CrateVersionListItem> comparator =
        "version".equalsIgnoreCase(order.getProperty())
            ? Comparator.comparing(
                CrateVersionListItem::version,
                new io.repsy.protocols.cargo.shared.crate.services.SemverComparator())
            : Comparator.comparing(CrateVersionListItem::createdAt);

    if (order.isDescending()) {
      comparator = comparator.reversed();
    }

    return comparator;
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

    this.crateMetaRepository
        .findByCrateIdAndVersion(crate.getId(), version)
        .ifPresent(
            meta -> {
              meta.setDownloads(meta.getDownloads() + 1);
              this.crateMetaRepository.save(meta);
            });
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

    final var mappedDeps =
        request.deps() == null
            ? null
            : request.deps().stream()
                .map(
                    dep -> {
                      boolean hasAlias = dep.explicitNameInToml() != null;

                      final var indexName = hasAlias ? dep.explicitNameInToml() : dep.name();
                      final var indexPackage = hasAlias ? dep.name() : null;

                      return new CrateIndexDep(
                          indexName,
                          dep.versionReq(),
                          dep.features(),
                          dep.optional(),
                          dep.defaultFeatures(),
                          dep.target(),
                          dep.kind(),
                          dep.registry(),
                          indexPackage);
                    })
                .toList();

    index.setDeps(this.toJson(mappedDeps));
    index.setCksum(request.cksum());
    index.setFeatures(this.toJson(request.features()));
    index.setFeatures2(this.toJson(request.features2()));
    index.setYanked(false);
    index.setLinks(request.links());
    index.setV(request.features2() != null ? 2 : 1);
    index.setRustVersion(request.rustVersion());

    this.crateIndexRepository.save(index);
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

  private void syncAuthors(final CargoCrate crate, final List<String> authorStrings) {

    crate.getAuthors().clear();

    for (final var authorStr : authorStrings) {
      final var author =
          this.authorRepository
              .findByAuthor(authorStr)
              .orElseGet(
                  () -> {
                    final var newAuthor = new CargoAuthor();
                    newAuthor.setAuthor(authorStr);
                    return this.authorRepository.save(newAuthor);
                  });

      crate.getAuthors().add(author);
    }
  }

  private void syncKeywords(final CargoCrate crate, final List<String> keywordStrings) {

    crate.getKeywords().clear();

    for (final var keywordStr : keywordStrings) {
      final var keyword =
          this.keywordRepository
              .findByKeyword(keywordStr)
              .orElseGet(
                  () -> {
                    final var newKeyword = new CargoKeyword();
                    newKeyword.setKeyword(keywordStr);
                    return this.keywordRepository.save(newKeyword);
                  });

      crate.getKeywords().add(keyword);
    }
  }

  private void syncCategories(final CargoCrate crate, final List<String> categoryStrings) {

    crate.getCategories().clear();

    for (final var categoryStr : categoryStrings) {
      final var category =
          this.categoryRepository
              .findByCategory(categoryStr)
              .orElseGet(
                  () -> {
                    final var newCategory = new CargoCategory();
                    newCategory.setCategory(categoryStr);
                    return this.categoryRepository.save(newCategory);
                  });

      crate.getCategories().add(category);
    }
  }

  private void updateCrateMaxVersion(final CargoCrate crate, final String newVers) {

    final var currentMax = crate.getMaxVersion();
    if (currentMax == null
        || new io.repsy.protocols.cargo.shared.crate.services.SemverComparator()
                .compare(newVers, currentMax)
            > 0) {
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

    final var normalizedName = normalizeName(name);

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

  private static String normalizeName(final String name) {
    return name.toLowerCase().replace('-', '_');
  }
}
