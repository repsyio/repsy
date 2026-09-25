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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.VersionKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.VersionMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmSearchCandidateRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NpmSearchServiceImpl")
class NpmSearchServiceImplTest {

  @Mock private NpmSearchCandidateRepository candidates;
  @Mock private PackageKeywordRepository keywords;
  @Mock private PackageMaintainerRepository maintainers;

  private final BaseRepoInfo<UUID> repo =
      BaseRepoInfo.<UUID>builder().name("npm").storageKey(UUID.randomUUID()).build();

  private NpmSearchServiceImpl service() {
    return this.service(NpmSearchServiceImpl.DEFAULT_MAX_CANDIDATES);
  }

  private NpmSearchServiceImpl service(final int maxCandidates) {
    return new NpmSearchServiceImpl(
        this.candidates, this.keywords, this.maintainers, maxCandidates);
  }

  private static NpmSearchCandidate candidate(
      final UUID versionId, final String scope, final String name, final String description) {
    return new NpmSearchCandidate(
        versionId,
        scope,
        name,
        "1.0.0",
        description,
        Instant.parse("2026-09-24T10:00:00Z"),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static VersionKeywordListItem keyword(final UUID versionId, final String keyword) {
    return new VersionKeywordListItem() {
      @Override
      public UUID getPackageVersionId() {
        return versionId;
      }

      @Override
      public String getKeyword() {
        return keyword;
      }
    };
  }

  private static VersionMaintainerListItem maintainer(final UUID versionId, final String name) {
    return new VersionMaintainerListItem() {
      @Override
      public UUID getPackageVersionId() {
        return versionId;
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getEmail() {
        return name + "@example.com";
      }
    };
  }

  private static NpmSearchQuery query(final String text, final String size, final String from) {
    return NpmSearchQuery.parse(text, size, from);
  }

  @Test
  @DisplayName("answers nothing, and loads nothing else, when no package matches")
  void noCandidates() {
    when(this.candidates.find(any(), any(), anyInt())).thenReturn(List.of());

    final var result = this.service().search(this.repo, query("x", null, null));

    assertThat(result.objects()).isEmpty();
    assertThat(result.total()).isZero();
    verify(this.keywords, never()).findAllByPackageVersionIdIn(anyCollection());
    verify(this.maintainers, never()).findAllByPackageVersionIdIn(anyCollection());
    verify(this.candidates, never()).count(any(), any());
  }

  @Test
  @DisplayName("asks the database for the repo, the query and the cap")
  void queriesTheRepository() {
    when(this.candidates.find(any(), any(), anyInt())).thenReturn(List.of());
    final var query = query("ab scope:acme", null, null);

    this.service(42).search(this.repo, query);

    verify(this.candidates).find(this.repo.getStorageKey(), query, 42);
  }

  @Test
  @DisplayName("scores the matches, pages them and loads the maintainers of the page only")
  void ranksPagesAndLoadsMaintainers() {
    final var padId = UUID.randomUUID();
    final var leftPadId = UUID.randomUUID();
    when(this.candidates.find(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                candidate(leftPadId, null, "left-pad", "pads left"),
                candidate(padId, "acme", "pad", "pad things")));
    when(this.keywords.findAllByPackageVersionIdIn(any()))
        .thenReturn(List.of(keyword(padId, "Strings"), keyword(leftPadId, "strings")));
    when(this.maintainers.findAllByPackageVersionIdIn(any()))
        .thenReturn(List.of(maintainer(padId, "ann"), maintainer(padId, "bob")));

    final var result = this.service().search(this.repo, query("pad", "1", "0"));

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.objects()).hasSize(1);
    final var hit = result.objects().getFirst();
    // The whole-name match ranks first, and its maintainers are the only ones that are loaded.
    assertThat(hit.pkg().name()).isEqualTo("pad");
    assertThat(hit.pkg().scope()).isEqualTo("acme");
    assertThat(hit.pkg().keywords()).containsExactly("Strings");
    assertThat(hit.pkg().maintainers()).extracting("username").containsExactly("ann", "bob");
    assertThat(hit.pkg().publisher().username()).isEqualTo("ann");
    assertThat(hit.score().finalScore()).isEqualTo(1.0);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> loaded = ArgumentCaptor.forClass(Collection.class);
    verify(this.maintainers).findAllByPackageVersionIdIn(loaded.capture());
    assertThat(loaded.getValue()).containsExactly(padId);
    verify(this.candidates, never()).count(any(), any());
  }

  @Test
  @DisplayName("the second page starts after the first and scores against the best hit")
  void secondPage() {
    final var padId = UUID.randomUUID();
    final var leftPadId = UUID.randomUUID();
    when(this.candidates.find(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                candidate(leftPadId, null, "left-pad", null), candidate(padId, null, "pad", null)));
    when(this.keywords.findAllByPackageVersionIdIn(any())).thenReturn(List.of());
    when(this.maintainers.findAllByPackageVersionIdIn(any())).thenReturn(List.of());

    final var result = this.service().search(this.repo, query("pad", "1", "1"));

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.objects()).hasSize(1);
    assertThat(result.objects().getFirst().pkg().name()).isEqualTo("left-pad");
    assertThat(result.objects().getFirst().score().finalScore()).isLessThan(0.01);
    assertThat(result.objects().getFirst().pkg().maintainers()).isEmpty();
  }

  @Test
  @DisplayName("total is the true count of the matches when the cap cut the loaded ones")
  void totalIsTheTrueCountWhenCapped() {
    final var loaded = new ArrayList<NpmSearchCandidate>();
    for (var i = 0; i < 3; i++) {
      loaded.add(candidate(UUID.randomUUID(), null, "pad-" + i, null));
    }
    when(this.candidates.find(any(), any(), anyInt())).thenReturn(loaded);
    when(this.candidates.count(any(), any())).thenReturn(4321L);
    when(this.keywords.findAllByPackageVersionIdIn(any())).thenReturn(List.of());
    when(this.maintainers.findAllByPackageVersionIdIn(any())).thenReturn(List.of());

    final var result = this.service(3).search(this.repo, query("pad", "10", "0"));

    assertThat(result.total()).isEqualTo(4321);
    assertThat(result.objects()).hasSize(3);
    verify(this.candidates).count(eq(this.repo.getStorageKey()), any());
  }

  @Test
  @DisplayName("total is the number of loaded matches, without a count query, below the cap")
  void totalBelowTheCap() {
    when(this.candidates.find(any(), any(), anyInt()))
        .thenReturn(List.of(candidate(UUID.randomUUID(), null, "pad", null)));
    when(this.keywords.findAllByPackageVersionIdIn(any())).thenReturn(List.of());
    when(this.maintainers.findAllByPackageVersionIdIn(any())).thenReturn(List.of());

    assertThat(this.service(2).search(this.repo, query("pad", null, null)).total()).isEqualTo(1);
    verify(this.candidates, never()).count(any(), any());
  }

  @Test
  @DisplayName("loads the keywords of many candidates in chunks of 1000")
  void chunksTheKeywordLookup() {
    final var loaded = new ArrayList<NpmSearchCandidate>();
    for (var i = 0; i < NpmSearchServiceImpl.ID_CHUNK_SIZE * 2 + 1; i++) {
      loaded.add(candidate(UUID.randomUUID(), null, "p" + i, null));
    }
    when(this.candidates.find(any(), any(), anyInt())).thenReturn(loaded);
    when(this.keywords.findAllByPackageVersionIdIn(any())).thenReturn(List.of());
    when(this.maintainers.findAllByPackageVersionIdIn(any())).thenReturn(List.of());

    final var result = this.service().search(this.repo, query("", "250", "0"));

    assertThat(result.total()).isEqualTo(loaded.size());
    assertThat(result.objects()).hasSize(250);
    verify(this.keywords, times(3)).findAllByPackageVersionIdIn(any());
  }
}
