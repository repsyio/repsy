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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.events.PgpVerifyAllSignaturesToggledEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** The recomputation of {@code signed} of all the versions of a repo on a toggle (RPS-1316). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignedRecomputeService (RPS-1316)")
class SignedRecomputeServiceTest {

  private static final UUID BEFORE_THE_FIRST = new UUID(0L, 0L);

  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock VersionSignatureService versionSignatureService;

  private SignedRecomputeService service;
  private final UUID repoId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    this.service =
        new SignedRecomputeService(this.artifactVersionRepository, this.versionSignatureService, 2);
  }

  private static List<UUID> ids(final int count) {
    return java.util.stream.IntStream.range(1, count + 1).mapToObj(i -> new UUID(0L, i)).toList();
  }

  private void pageAfter(final UUID after, final List<UUID> page) {
    when(this.artifactVersionRepository.findIdsByRepoIdAfter(
            eq(this.repoId), eq(after), any(Pageable.class)))
        .thenReturn(page);
  }

  @Test
  @DisplayName("walks the versions in pages of ids, each after the last id of the page before")
  void walksAllPages() {
    final var all = ids(5);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 2));
    this.pageAfter(all.get(1), all.subList(2, 4));
    this.pageAfter(all.get(3), all.subList(4, 5));

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(5, 0));
    final var order = inOrder(this.versionSignatureService);
    all.forEach(id -> order.verify(this.versionSignatureService).recompute(id));
  }

  @Test
  @DisplayName("a page that is exactly full is followed by a look for the next one")
  void anExactlyFullLastPageIsFollowedByAnEmptyOne() {
    final var all = ids(2);
    this.pageAfter(BEFORE_THE_FIRST, all);
    this.pageAfter(all.get(1), List.of());

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result.recomputed()).isEqualTo(2);
    verify(this.artifactVersionRepository, times(2))
        .findIdsByRepoIdAfter(eq(this.repoId), any(UUID.class), any(Pageable.class));
  }

  @Test
  @DisplayName("a repo without versions recomputes nothing")
  void anEmptyRepo() {
    this.pageAfter(BEFORE_THE_FIRST, List.of());

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(0, 0));
    verify(this.versionSignatureService, never()).recompute(any());
  }

  @Test
  @DisplayName("a version that fails is counted and the rest are still recomputed")
  void aFailureDoesNotAbortTheRest() {
    final var all = ids(3);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 2));
    this.pageAfter(all.get(1), all.subList(2, 3));
    doThrow(new IllegalStateException("storage is down"))
        .when(this.versionSignatureService)
        .recompute(all.get(0));

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(2, 1));
    verify(this.versionSignatureService).recompute(all.get(1));
    verify(this.versionSignatureService).recompute(all.get(2));
  }

  @Test
  @DisplayName("the event of a toggle recomputes the repo it names")
  void theEventRecomputesItsRepo() {
    this.pageAfter(BEFORE_THE_FIRST, ids(1));

    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    verify(this.versionSignatureService).recompute(ids(1).getFirst());
  }

  @Test
  @DisplayName("a batch size below one is read as one")
  void aBatchSizeBelowOneIsOne() {
    final var single =
        new SignedRecomputeService(this.artifactVersionRepository, this.versionSignatureService, 0);
    final var all = ids(2);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 1));
    this.pageAfter(all.get(0), all.subList(1, 2));
    this.pageAfter(all.get(1), List.of());

    assertThat(single.recomputeRepo(this.repoId).recomputed()).isEqualTo(2);
  }
}
