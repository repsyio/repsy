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
package io.repsy.os.server.protocols.npm.shared.npm_package.services;

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmSearchCandidateRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.protocols.npm.shared.search.NpmSearchDocument;
import io.repsy.protocols.npm.shared.search.NpmSearchPerson;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import io.repsy.protocols.npm.shared.search.NpmSearchResult;
import io.repsy.protocols.npm.shared.search.NpmSearchScorer;
import io.repsy.protocols.npm.shared.search.NpmSearchService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searches the latest versions of the packages of one repository. The database applies every filter
 * of the query (terms, scope, keywords), counts the matches and returns the best {@code
 * maxCandidates} of them, whole-name matches first; the scoring orders those in memory, and only
 * the maintainers of the requested page are loaded. So {@code total} is the number of matches even
 * when more than {@code maxCandidates} match, and a search can page through the first {@code
 * maxCandidates} of them.
 */
@Service
@Transactional(readOnly = true)
@NullMarked
public class NpmSearchServiceImpl implements NpmSearchService<UUID> {

  /**
   * The most packages one search loads by default, so that an empty search of a huge repo stays
   * cheap.
   */
  static final int DEFAULT_MAX_CANDIDATES = 5000;

  /** How many ids one {@code in} clause takes. */
  static final int ID_CHUNK_SIZE = 1000;

  private final NpmSearchCandidateRepository candidateRepository;
  private final PackageKeywordRepository keywordRepository;
  private final PackageMaintainerRepository maintainerRepository;
  private final int maxCandidates;

  public NpmSearchServiceImpl(
      final NpmSearchCandidateRepository candidateRepository,
      final PackageKeywordRepository keywordRepository,
      final PackageMaintainerRepository maintainerRepository,
      @Value("${repsy.npm.search.max-candidates:" + DEFAULT_MAX_CANDIDATES + "}")
          final int maxCandidates) {
    this.candidateRepository = candidateRepository;
    this.keywordRepository = keywordRepository;
    this.maintainerRepository = maintainerRepository;
    this.maxCandidates = maxCandidates;
  }

  @Override
  public NpmSearchResult search(final BaseRepoInfo<UUID> repoInfo, final NpmSearchQuery query) {
    final var now = Instant.now();

    final var candidates =
        this.candidateRepository.find(repoInfo.getStorageKey(), query, this.maxCandidates);

    if (candidates.isEmpty()) {
      return NpmSearchResult.empty(now);
    }

    final var keywords = this.keywordsByVersion(candidates);
    final var documents = new ArrayList<NpmSearchDocument>();
    final var versionIds = new HashMap<String, UUID>();

    for (final var candidate : candidates) {
      final var document =
          toDocument(candidate, keywords.getOrDefault(candidate.versionId(), List.of()));

      documents.add(document);
      versionIds.put(document.fullName(), candidate.versionId());
    }

    final var ranked = NpmSearchScorer.rank(documents, query);

    // Every filter is in SQL, so the loaded rows are all matches: when they are fewer than the
    // cap they are all there are, and otherwise the database counts them.
    final var total =
        candidates.size() < this.maxCandidates
            ? candidates.size()
            : this.candidateRepository.count(repoInfo.getStorageKey(), query);
    final var page = ranked.stream().skip(query.from()).limit(query.size()).toList();
    final var best = ranked.stream().mapToDouble(NpmSearchScorer.Scored::score).max().orElse(1.0);

    final var maintainers =
        this.maintainersByVersion(
            page.stream().map(scored -> versionIds.get(scored.document().fullName())).toList());

    final var maintainersByName = new HashMap<String, List<NpmSearchPerson>>();
    for (final var scored : page) {
      final var name = scored.document().fullName();

      maintainersByName.put(name, maintainers.getOrDefault(versionIds.get(name), List.of()));
    }

    return NpmSearchResult.of(page, total, best, maintainersByName, now);
  }

  private Map<UUID, List<String>> keywordsByVersion(final List<NpmSearchCandidate> candidates) {
    final var keywords = new LinkedHashMap<UUID, List<String>>();

    for (final var chunk :
        chunks(candidates.stream().map(NpmSearchCandidate::versionId).toList())) {
      for (final var keyword : this.keywordRepository.findAllByPackageVersionIdIn(chunk)) {
        keywords
            .computeIfAbsent(keyword.getPackageVersionId(), _ -> new ArrayList<>())
            .add(keyword.getKeyword());
      }
    }

    return keywords;
  }

  private Map<UUID, List<NpmSearchPerson>> maintainersByVersion(final List<UUID> versionIds) {
    final var maintainers = new LinkedHashMap<UUID, List<NpmSearchPerson>>();

    for (final var chunk : chunks(versionIds)) {
      for (final var maintainer : this.maintainerRepository.findAllByPackageVersionIdIn(chunk)) {
        maintainers
            .computeIfAbsent(maintainer.getPackageVersionId(), _ -> new ArrayList<>())
            .add(new NpmSearchPerson(maintainer.getName(), maintainer.getEmail()));
      }
    }

    return maintainers;
  }

  private static <T> List<List<T>> chunks(final List<T> values) {
    final var chunks = new ArrayList<List<T>>();

    for (var start = 0; start < values.size(); start += ID_CHUNK_SIZE) {
      chunks.add(values.subList(start, Math.min(values.size(), start + ID_CHUNK_SIZE)));
    }

    return chunks;
  }

  private static NpmSearchDocument toDocument(
      final NpmSearchCandidate candidate, final List<String> keywords) {

    return new NpmSearchDocument(
        candidate.scope(),
        candidate.name(),
        candidate.latest(),
        candidate.description(),
        List.copyOf(keywords),
        candidate.createdAt(),
        candidate.homepage(),
        candidate.repositoryUrl(),
        candidate.bugsUrl(),
        candidate.authorName(),
        candidate.authorEmail(),
        candidate.authorUrl());
  }
}
