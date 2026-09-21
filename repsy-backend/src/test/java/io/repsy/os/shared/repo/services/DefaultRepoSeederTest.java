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
package io.repsy.os.shared.repo.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultRepoSeeder")
class DefaultRepoSeederTest {

  private static final String NAME = "maven";
  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Mock private RepoTxService repoTxService;

  private Consumer<UUID> storageCreator;
  private DefaultRepoSeeder seeder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    this.storageCreator = mock(Consumer.class);
    this.seeder = new DefaultRepoSeeder(this.repoTxService);
  }

  private static RepoInfo repoInfo(final RepoType type) {
    return RepoInfo.builder().id(STORAGE_KEY).storageKey(STORAGE_KEY).name(NAME).type(type).build();
  }

  private void seedMaven() {
    this.seeder.seed(NAME, RepoType.MAVEN, this.storageCreator);
  }

  @Test
  @DisplayName("creates a private repo without description, then its storage directory")
  void missingRepoIsCreatedWithItsDirectory() {
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.empty());
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null))
        .thenReturn(repoInfo(RepoType.MAVEN));

    this.seedMaven();

    verify(this.repoTxService).createRepo(NAME, RepoType.MAVEN, true, null);
    verify(this.storageCreator).accept(STORAGE_KEY);
  }

  @Test
  @DisplayName("skips the row of an existing repo of the type but still ensures its directory")
  void existingRepoKeepsItsRowAndGetsItsDirectory() {
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.of(repoInfo(RepoType.MAVEN)));

    assertThatCode(this::seedMaven).doesNotThrowAnyException();

    verify(this.repoTxService, never()).createRepo(any(), any(), anyBoolean(), any());
    verify(this.storageCreator).accept(STORAGE_KEY);
  }

  @Test
  @DisplayName("leaves a name that belongs to another protocol alone, creating no directory")
  void nameTakenByAnotherTypeIsNotSeeded() {
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.of(repoInfo(RepoType.NPM)));

    assertThatCode(this::seedMaven).doesNotThrowAnyException();

    verify(this.repoTxService, never()).createRepo(any(), any(), anyBoolean(), any());
    verifyNoInteractions(this.storageCreator);
  }

  @Test
  @DisplayName("recovers from a concurrent duplicate event that won with repoExists")
  void concurrentCreateRejectedAsDuplicateIsRecovered() {
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.empty());
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null))
        .thenThrow(new ItemAlreadyExistException("repoExists"));
    when(this.repoTxService.getRepoByNameAndType(NAME, RepoType.MAVEN))
        .thenReturn(Optional.of(repoInfo(RepoType.MAVEN)));

    assertThatCode(this::seedMaven).doesNotThrowAnyException();

    verify(this.storageCreator).accept(STORAGE_KEY);
  }

  @Test
  @DisplayName("recovers from a concurrent duplicate event that lost at the unique index")
  void concurrentCreateRejectedByIndexIsRecovered() {
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.empty());
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null))
        .thenThrow(new DataIntegrityViolationException("ux_repo__name"));
    when(this.repoTxService.getRepoByNameAndType(NAME, RepoType.MAVEN))
        .thenReturn(Optional.of(repoInfo(RepoType.MAVEN)));

    assertThatCode(this::seedMaven).doesNotThrowAnyException();

    verify(this.storageCreator).accept(STORAGE_KEY);
  }

  @Test
  @DisplayName("rethrows a duplicate failure when no repo of the type exists afterwards")
  void duplicateFailureWithoutRepoIsRethrown() {
    final var failure = new ItemAlreadyExistException("repoExists");
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.empty());
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null)).thenThrow(failure);
    when(this.repoTxService.getRepoByNameAndType(NAME, RepoType.MAVEN))
        .thenReturn(Optional.empty());

    assertThatThrownBy(this::seedMaven).isSameAs(failure);

    verifyNoInteractions(this.storageCreator);
  }

  @Test
  @DisplayName("does not swallow other failures of the row creation")
  void otherCreateFailuresPropagate() {
    final var failure = new IllegalStateException("database down");
    when(this.repoTxService.findRepoByName(NAME)).thenReturn(Optional.empty());
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null)).thenThrow(failure);

    assertThatThrownBy(this::seedMaven).isSameAs(failure);

    verify(this.repoTxService, never()).getRepoByNameAndType(any(), any());
    verifyNoInteractions(this.storageCreator);
  }

  @Test
  @DisplayName("a failed directory creation is repaired by the next run without a second row")
  void failedStorageIsHealedByTheNextRun() {
    final var failure = new IllegalStateException("disk full");
    when(this.repoTxService.findRepoByName(NAME))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(repoInfo(RepoType.MAVEN)));
    when(this.repoTxService.createRepo(NAME, RepoType.MAVEN, true, null))
        .thenReturn(repoInfo(RepoType.MAVEN));
    doThrow(failure).doNothing().when(this.storageCreator).accept(eq(STORAGE_KEY));

    assertThatThrownBy(this::seedMaven).isSameAs(failure);
    assertThatCode(this::seedMaven).doesNotThrowAnyException();

    verify(this.repoTxService).createRepo(NAME, RepoType.MAVEN, true, null);
    verify(this.storageCreator, times(2)).accept(STORAGE_KEY);
  }
}
