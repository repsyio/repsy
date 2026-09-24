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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.VersionKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.VersionMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
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
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
@DisplayName("NpmSearchServiceImpl")
class NpmSearchServiceImplTest {

  @Mock private NpmPackageRepository packages;
  @Mock private PackageKeywordRepository keywords;
  @Mock private PackageMaintainerRepository maintainers;

  private final BaseRepoInfo<UUID> repo =
      BaseRepoInfo.<UUID>builder().name("npm").storageKey(UUID.randomUUID()).build();

  private NpmSearchServiceImpl service() {
    return new NpmSearchServiceImpl(this.packages, this.keywords, this.maintainers);
  }

  private record Candidate(UUID versionId, String scope, String name, String description)
      implements NpmSearchCandidate {
    @Override
    public UUID getVersionId() {
      return this.versionId;
    }

    @Override
    public String getScope() {
      return this.scope;
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public String getLatest() {
      return "1.0.0";
    }

    @Override
    public String getDescription() {
      return this.description;
    }

    @Override
    public Instant getCreatedAt() {
      return Instant.parse("2026-09-24T10:00:00Z");
    }

    @Override
    public String getAuthorName() {
      return null;
    }

    @Override
    public String getAuthorEmail() {
      return null;
    }

    @Override
    public String getAuthorUrl() {
      return null;
    }

    @Override
    public String getHomepage() {
      return null;
    }

    @Override
    public String getRepositoryUrl() {
      return null;
    }

    @Override
    public String getBugsUrl() {
      return null;
    }
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
  @DisplayName("answers nothing, and loads nothing else, when no package is a candidate")
  void noCandidates() {
    when(this.packages.findSearchCandidates(any(), any(), any(), any())).thenReturn(List.of());

    final var result = this.service().search(this.repo, query("x", null, null));

    assertThat(result.objects()).isEmpty();
    assertThat(result.total()).isZero();
    verify(this.keywords, never()).findAllByPackageVersionIdIn(anyCollection());
    verify(this.maintainers, never()).findAllByPackageVersionIdIn(anyCollection());
  }

  @Test
  @DisplayName("asks the database for the repo, the scope and the escaped longest term")
  void queriesTheRepository() {
    when(this.packages.findSearchCandidates(any(), any(), any(), any())).thenReturn(List.of());

    this.service().search(this.repo, query("ab a_b%c!d scope:@Acme", null, null));

    final var pattern = ArgumentCaptor.forClass(String.class);
    final var limit = ArgumentCaptor.forClass(Limit.class);
    verify(this.packages)
        .findSearchCandidates(
            eq(this.repo.getStorageKey()), eq("acme"), pattern.capture(), limit.capture());
    assertThat(pattern.getValue()).isEqualTo("%a!_b!%c!!d%");
    assertThat(limit.getValue().max()).isEqualTo(NpmSearchServiceImpl.MAX_SEARCH_CANDIDATES);
  }

  @Test
  @DisplayName("does not narrow by a term when there is none")
  void noTerm() {
    when(this.packages.findSearchCandidates(any(), any(), any(), any())).thenReturn(List.of());

    this.service().search(this.repo, query("@ ", null, null));
    this.service().search(this.repo, query("", null, null));

    verify(this.packages, times(2)).findSearchCandidates(any(), eq(null), eq(null), any());
  }

  @Test
  @DisplayName("strips the @ of a scoped term before it narrows")
  void scopedTerm() {
    when(this.packages.findSearchCandidates(any(), any(), any(), any())).thenReturn(List.of());

    this.service().search(this.repo, query("@acme/pad", null, null));

    verify(this.packages).findSearchCandidates(any(), eq(null), eq("%acme/pad%"), any());
  }

  @Test
  @DisplayName(
      "filters by every term and keyword, pages the ranked hits and loads their maintainers")
  void ranksPagesAndLoadsMaintainers() {
    final var padId = UUID.randomUUID();
    final var leftPadId = UUID.randomUUID();
    final var otherId = UUID.randomUUID();
    when(this.packages.findSearchCandidates(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new Candidate(leftPadId, null, "left-pad", "pads left"),
                new Candidate(otherId, "acme", "other", "unrelated"),
                new Candidate(padId, "acme", "pad", "pad things")));
    when(this.keywords.findAllByPackageVersionIdIn(any()))
        .thenReturn(List.of(keyword(padId, "Strings"), keyword(leftPadId, "strings")));
    when(this.maintainers.findAllByPackageVersionIdIn(any()))
        .thenReturn(List.of(maintainer(padId, "ann"), maintainer(padId, "bob")));

    final var result = this.service().search(this.repo, query("pad keywords:strings", "1", "0"));

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.objects()).hasSize(1);
    final var hit = result.objects().getFirst();
    // The whole name match ranks first, and its maintainers are the only ones that are loaded.
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
  }

  @Test
  @DisplayName("the second page starts after the first and scores against the best hit")
  void secondPage() {
    final var padId = UUID.randomUUID();
    final var leftPadId = UUID.randomUUID();
    when(this.packages.findSearchCandidates(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new Candidate(leftPadId, null, "left-pad", null),
                new Candidate(padId, null, "pad", null)));
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
  @DisplayName("loads the keywords of many candidates in chunks of 1000")
  void chunksTheKeywordLookup() {
    final var candidates = new ArrayList<NpmSearchCandidate>();
    for (var i = 0; i < NpmSearchServiceImpl.ID_CHUNK_SIZE * 2 + 1; i++) {
      candidates.add(new Candidate(UUID.randomUUID(), null, "p" + i, null));
    }
    when(this.packages.findSearchCandidates(any(), any(), any(), any())).thenReturn(candidates);
    when(this.keywords.findAllByPackageVersionIdIn(any())).thenReturn(List.of());
    when(this.maintainers.findAllByPackageVersionIdIn(any())).thenReturn(List.of());

    final var result = this.service().search(this.repo, query("", "250", "0"));

    assertThat(result.total()).isEqualTo(candidates.size());
    assertThat(result.objects()).hasSize(250);
    verify(this.keywords, times(3)).findAllByPackageVersionIdIn(any());
  }
}
