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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
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
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("CargoCrateService")
class CargoCrateServiceTest {

  @Mock RepoRepository repoRepository;
  @Mock CargoCrateRepository crateRepository;
  @Mock CargoCrateIndexRepository crateIndexRepository;
  @Mock CargoCrateMetaRepository crateMetaRepository;
  @Mock CargoAuthorRepository authorRepository;
  @Mock CargoKeywordRepository keywordRepository;
  @Mock CargoCategoryRepository categoryRepository;
  @Mock CargoCrateConverter crateConverter;
  @Mock ObjectMapper objectMapper;

  @InjectMocks CargoCrateServiceImpl cargoCrateService;

  @BeforeEach
  void stubGlobalRows() {
    this.stubGlobalRowsRead();
  }

  @Nested
  @DisplayName("publish()")
  class Publish {

    @Test
    @DisplayName("throws ItemNotFoundException when repo does not exist")
    void throwsExceptionWhenRepoNotFound() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      when(CargoCrateServiceTest.this.repoRepository.findById(repoInfo.getId()))
          .thenReturn(Optional.empty());

      final var request = CargoCrateServiceTest.this.createPublishRequest("test-crate", "1.0.0");

      assertThatThrownBy(
              () -> CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("repoNotFound");
    }

    @Test
    @DisplayName("throws ItemAlreadyExistException when crate version already exists in registry")
    void throwsExceptionWhenVersionExists() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);
      final var request = CargoCrateServiceTest.this.createPublishRequest("test-crate", "1.0.0");

      final var existingCrate = new CargoCrate();
      existingCrate.setId(UUID.randomUUID());

      when(CargoCrateServiceTest.this.repoRepository.findById(repoId))
          .thenReturn(Optional.of(new Repo()));
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(existingCrate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              existingCrate.getId(), "1.0.0"))
          .thenReturn(Optional.of(new CargoCrateIndex()));

      assertThatThrownBy(
              () -> CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("crateVersionAlreadyExists");
    }

    @Test
    @DisplayName("creates new crate, syncs metadata and saves successfully")
    void createsNewCrateAndSyncsMetadata() throws Exception {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);
      final var request = CargoCrateServiceTest.this.createPublishRequest("my-Crate", "1.0.0");

      when(CargoCrateServiceTest.this.repoRepository.findById(repoId))
          .thenReturn(Optional.of(new Repo()));
      final var insertedCrate = new CargoCrate();
      insertedCrate.setId(UUID.randomUUID());
      insertedCrate.setName("my_crate");
      insertedCrate.setOriginalName("my-Crate");
      insertedCrate.setMaxVersion("1.0.0");
      // Absent before the insert, read back after it.
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "my_crate"))
          .thenReturn(Optional.empty(), Optional.of(insertedCrate));
      when(CargoCrateServiceTest.this.crateRepository.insertIfAbsent(
              any(),
              eq(repoId),
              eq("my_crate"),
              eq("my-Crate"),
              eq("1.0.0"),
              any(),
              any(),
              any(),
              anyBoolean(),
              any()))
          .thenReturn(1);

      when(CargoCrateServiceTest.this.crateRepository.save(any(CargoCrate.class)))
          .thenAnswer(i -> i.getArgument(0));

      CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request);

      final var crateCaptor = ArgumentCaptor.forClass(CargoCrate.class);
      verify(CargoCrateServiceTest.this.crateRepository).save(crateCaptor.capture());

      final var savedCrate = crateCaptor.getValue();
      assertThat(savedCrate.getName()).isEqualTo("my_crate");
      assertThat(savedCrate.getOriginalName()).isEqualTo("my-Crate");
      assertThat(savedCrate.getMaxVersion()).isEqualTo("1.0.0");

      // The publish time orders the sparse index (RPS-1605).
      final var indexCaptor = ArgumentCaptor.forClass(CargoCrateIndex.class);
      verify(CargoCrateServiceTest.this.crateIndexRepository).save(indexCaptor.capture());
      assertThat(indexCaptor.getValue().getCreatedAt()).isNotNull();
      verify(CargoCrateServiceTest.this.crateMetaRepository).save(any(CargoCrateMeta.class));
    }

    @Test
    @DisplayName("inserts the global authors, keywords and categories with insert-if-absent")
    void insertsGlobalRowsWithInsertIfAbsent() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);
      final var request = CargoCrateServiceTest.this.createPublishRequest("test-crate", "1.0.0");

      final var existingCrate = new CargoCrate();
      existingCrate.setId(UUID.randomUUID());
      when(CargoCrateServiceTest.this.repoRepository.findById(repoId))
          .thenReturn(Optional.of(new Repo()));
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(existingCrate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              existingCrate.getId(), "1.0.0"))
          .thenReturn(Optional.empty());
      CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request);

      // The values are inserted in a fixed order, so two publishes cannot wait on each other.
      final var order =
          inOrder(
              CargoCrateServiceTest.this.authorRepository,
              CargoCrateServiceTest.this.keywordRepository,
              CargoCrateServiceTest.this.categoryRepository);
      order
          .verify(CargoCrateServiceTest.this.authorRepository)
          .insertIfAbsent(any(UUID.class), eq("Author1"));
      order
          .verify(CargoCrateServiceTest.this.authorRepository)
          .insertIfAbsent(any(UUID.class), eq("Author2"));
      order
          .verify(CargoCrateServiceTest.this.keywordRepository)
          .insertIfAbsent(any(UUID.class), eq("db"));
      order
          .verify(CargoCrateServiceTest.this.keywordRepository)
          .insertIfAbsent(any(UUID.class), eq("web"));
      order
          .verify(CargoCrateServiceTest.this.categoryRepository)
          .insertIfAbsent(any(UUID.class), eq("api"));
      verify(CargoCrateServiceTest.this.authorRepository, never()).save(any(CargoAuthor.class));
      verify(CargoCrateServiceTest.this.keywordRepository, never()).save(any(CargoKeyword.class));
      verify(CargoCrateServiceTest.this.categoryRepository, never()).save(any(CargoCategory.class));
      assertThat(existingCrate.getAuthors()).hasSize(2);
      assertThat(existingCrate.getKeywords()).hasSize(2);
      assertThat(existingCrate.getCategories()).hasSize(1);
    }

    @Test
    @DisplayName("updates existing crate with new version and recalculates max version")
    void updatesExistingCrateWithNewVersion() throws Exception {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);
      final var request = CargoCrateServiceTest.this.createPublishRequest("test-crate", "2.0.0");

      final var existingCrate = new CargoCrate();
      existingCrate.setId(UUID.randomUUID());
      existingCrate.setName("test_crate");
      existingCrate.setMaxVersion("1.0.0");

      when(CargoCrateServiceTest.this.repoRepository.findById(repoId))
          .thenReturn(Optional.of(new Repo()));
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(existingCrate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              existingCrate.getId(), "2.0.0"))
          .thenReturn(Optional.empty());

      CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request);

      verify(CargoCrateServiceTest.this.crateRepository).save(existingCrate);
      assertThat(existingCrate.getMaxVersion()).isEqualTo("2.0.0");
      verify(CargoCrateServiceTest.this.crateIndexRepository).save(any(CargoCrateIndex.class));
    }
  }

  @Nested
  @DisplayName("publish() with a files writer (RPS-1124)")
  class PublishWithFiles {

    private static final String INDEX_CONSTRAINT = "ux_cargo_crate_index__crate_id_vers";

    private final UUID repoId = UUID.randomUUID();
    private final BaseUsages usages = BaseUsages.builder().diskUsage(42L).build();
    private BaseRepoInfo<UUID> repoInfo;
    private CargoCrate existingCrate;

    @BeforeEach
    void existingCrateOfTheRepo() {
      this.repoInfo = CargoCrateServiceTest.this.createRepoInfo(this.repoId);
      this.existingCrate = new CargoCrate();
      this.existingCrate.setId(UUID.randomUUID());
      this.existingCrate.setName("test_crate");
      this.existingCrate.setMaxVersion("1.0.0");

      lenient()
          .when(CargoCrateServiceTest.this.repoRepository.findById(this.repoId))
          .thenReturn(Optional.of(new Repo()));
      lenient()
          .when(
              CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(
                  this.repoId, "test_crate"))
          .thenReturn(Optional.of(this.existingCrate));
    }

    private CratePublishRequest request() {
      return CargoCrateServiceTest.this.createPublishRequest("test-crate", "2.0.0");
    }

    private DataIntegrityViolationException violation(final String state, final String message) {
      return new DataIntegrityViolationException(
          "could not execute statement", new SQLException(message, state));
    }

    @Test
    @DisplayName("writes and flushes the version rows before it runs the files writer")
    void writesTheRowsBeforeTheFiles() throws Exception {
      final var order = inOrder(CargoCrateServiceTest.this.crateIndexRepository);
      final var writerRan = new AtomicBoolean();

      final var result =
          CargoCrateServiceTest.this.cargoCrateService.publish(
              this.repoInfo,
              this.request(),
              "2021",
              () -> {
                order.verify(CargoCrateServiceTest.this.crateIndexRepository).flush();
                writerRan.set(true);
                return this.usages;
              });

      assertThat(result).isSameAs(this.usages);
      assertThat(writerRan).isTrue();
      verify(CargoCrateServiceTest.this.crateMetaRepository).save(any(CargoCrateMeta.class));
    }

    @Test
    @DisplayName("answers a unique violation of the version index as an existing version")
    void mapsTheVersionIndexToAConflict() {
      final var writerRan = new AtomicBoolean();
      doThrow(this.violation("23505", "duplicate key value violates " + INDEX_CONSTRAINT))
          .when(CargoCrateServiceTest.this.crateIndexRepository)
          .flush();

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.publish(
                      this.repoInfo,
                      this.request(),
                      null,
                      () -> {
                        writerRan.set(true);
                        return this.usages;
                      }))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("crateVersionAlreadyExists");

      assertThat(writerRan).as("the files are never written for a version that lost").isFalse();
    }

    @Test
    @DisplayName("does not answer another violation as an existing version")
    void leavesOtherViolationsAsServerErrors() {
      final var writerRan = new AtomicBoolean();
      final var other = this.violation("23514", "violates check constraint ch_other");
      doThrow(other).when(CargoCrateServiceTest.this.crateIndexRepository).flush();

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.publish(
                      this.repoInfo,
                      this.request(),
                      null,
                      () -> {
                        writerRan.set(true);
                        return this.usages;
                      }))
          .isSameAs(other);

      assertThat(writerRan).isFalse();
    }

    @Test
    @DisplayName("does not answer a unique violation of another index as an existing version")
    void leavesOtherUniqueIndexesAsServerErrors() {
      final var other = this.violation("23505", "duplicate key value violates ux_cargo_author");
      doThrow(other).when(CargoCrateServiceTest.this.crateIndexRepository).flush();

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.publish(
                      this.repoInfo, this.request(), null, () -> this.usages))
          .isSameAs(other);
    }

    @Test
    @DisplayName("lets a failure of the files writer through, for the transaction to roll back")
    void propagatesAWriterFailure() {
      final var failure = new IOException("disk full");

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.publish(
                      this.repoInfo,
                      this.request(),
                      null,
                      () -> {
                        throw failure;
                      }))
          .isSameAs(failure);
    }

    @Test
    @DisplayName("inserts a first version of a new crate without failing when it loses the insert")
    void aLostCrateInsertReadsTheWinnersCrateBack() throws Exception {
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(
              this.repoId, "test_crate"))
          .thenReturn(Optional.empty(), Optional.of(this.existingCrate));
      when(CargoCrateServiceTest.this.crateRepository.insertIfAbsent(
              any(),
              eq(this.repoId),
              eq("test_crate"),
              eq("test-crate"),
              eq("2.0.0"),
              any(),
              any(),
              any(),
              anyBoolean(),
              any()))
          .thenReturn(0);
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              this.existingCrate.getId(), "2.0.0"))
          .thenReturn(Optional.empty());

      final var result =
          CargoCrateServiceTest.this.cargoCrateService.publish(
              this.repoInfo, this.request(), null, () -> this.usages);

      assertThat(result).isSameAs(this.usages);
      verify(CargoCrateServiceTest.this.crateIndexRepository).save(any(CargoCrateIndex.class));
    }

    @Test
    @DisplayName("turns away a version the winner of a crate insert has already recorded")
    void aLostCrateInsertStillChecksTheVersion() {
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(
              this.repoId, "test_crate"))
          .thenReturn(Optional.empty(), Optional.of(this.existingCrate));
      when(CargoCrateServiceTest.this.crateRepository.insertIfAbsent(
              any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
          .thenReturn(0);
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              this.existingCrate.getId(), "2.0.0"))
          .thenReturn(Optional.of(new CargoCrateIndex()));
      final var writerRan = new AtomicBoolean();

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.publish(
                      this.repoInfo,
                      this.request(),
                      null,
                      () -> {
                        writerRan.set(true);
                        return this.usages;
                      }))
          .isInstanceOf(ItemAlreadyExistException.class);

      assertThat(writerRan).isFalse();
    }
  }

  @Nested
  @DisplayName("yank() & unyank()")
  class YankUnyank {

    @Test
    @DisplayName("sets yanked to true when crate is yanked")
    void setsYankedToTrue() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());

      final var index = new CargoCrateIndex();
      index.setYanked(false);

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.of(index));

      CargoCrateServiceTest.this.cargoCrateService.yank(repoInfo, "test-crate", "1.0.0");

      assertThat(index.isYanked()).isTrue();
      verify(CargoCrateServiceTest.this.crateIndexRepository).save(index);
    }

    @Test
    @DisplayName("sets yanked to false when crate is unyanked")
    void setsYankedToFalse() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());

      final var index = new CargoCrateIndex();
      index.setYanked(true);

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.of(index));

      CargoCrateServiceTest.this.cargoCrateService.unyank(repoInfo, "test-crate", "1.0.0");

      assertThat(index.isYanked()).isFalse();
      verify(CargoCrateServiceTest.this.crateIndexRepository).save(index);
    }
  }

  @Nested
  @DisplayName("deleteCrate() & deleteCrateVersion()")
  class DeleteCrate {

    @Test
    @DisplayName("deletes entire crate")
    void deletesEntireCrate() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));

      CargoCrateServiceTest.this.cargoCrateService.deleteCrate(repoInfo, "test-crate");

      verify(CargoCrateServiceTest.this.crateRepository).delete(crate);
    }

    @Test
    @DisplayName("deletes specific version and recalculates max version properly")
    void deletesVersionAndRecalculatesMax() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());
      crate.setMaxVersion("1.1.0");

      final var index = new CargoCrateIndex();
      final var meta = new CargoCrateMeta();

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              crate.getId(), "1.1.0"))
          .thenReturn(Optional.of(index));
      when(CargoCrateServiceTest.this.crateMetaRepository.findByCrateIdAndVersion(
              crate.getId(), "1.1.0"))
          .thenReturn(Optional.of(meta));

      final var remainingIndex = new CargoCrateIndex();
      remainingIndex.setVers("1.0.0");
      when(CargoCrateServiceTest.this.crateIndexRepository.findAllByCrateId(crate.getId()))
          .thenReturn(List.of(remainingIndex));

      CargoCrateServiceTest.this.cargoCrateService.deleteCrateVersion(
          repoInfo, "test-crate", "1.1.0");

      verify(CargoCrateServiceTest.this.crateIndexRepository).delete(index);
      verify(CargoCrateServiceTest.this.crateMetaRepository).delete(meta);

      assertThat(crate.getMaxVersion()).isEqualTo("1.0.0");
      verify(CargoCrateServiceTest.this.crateRepository).save(crate);
    }

    @Test
    @DisplayName("deletes entire crate if the deleted version was the last one")
    void deletesCrateIfLastVersionDeleted() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());

      final var index = new CargoCrateIndex();
      final var meta = new CargoCrateMeta();

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.of(index));
      when(CargoCrateServiceTest.this.crateMetaRepository.findByCrateIdAndVersion(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.of(meta));

      when(CargoCrateServiceTest.this.crateIndexRepository.findAllByCrateId(crate.getId()))
          .thenReturn(List.of());

      CargoCrateServiceTest.this.cargoCrateService.deleteCrateVersion(
          repoInfo, "test-crate", "1.0.0");

      verify(CargoCrateServiceTest.this.crateRepository).delete(crate);
    }
  }

  @Nested
  @DisplayName("Data Retrieval (Getters)")
  class Getters {

    @Test
    @DisplayName("getCrate() returns properly mapped CrateInfo")
    void returnsMappedCrateInfo() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateConverter.toCrateInfo(crate))
          .thenReturn(
              new BaseCrateInfo(
                  UUID.randomUUID(),
                  "test_crate",
                  "test-crate",
                  "1.0",
                  0,
                  null,
                  null,
                  null,
                  List.of(),
                  List.of(),
                  List.of(),
                  true));

      final var result =
          CargoCrateServiceTest.this.cargoCrateService.getCrate(repoInfo, "test-crate");

      assertThat(result.getName()).isEqualTo("test_crate");
      verify(CargoCrateServiceTest.this.crateConverter).toCrateInfo(crate);
    }

    @Test
    @DisplayName("getCrateVersion() throws exception if meta not found")
    void getCrateVersionThrowsIfMetaNotFound() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());
      final var index = new CargoCrateIndex();

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));
      when(CargoCrateServiceTest.this.crateIndexRepository.findByCrateIdAndVers(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.of(index));
      when(CargoCrateServiceTest.this.crateMetaRepository.findByCrateIdAndVersion(
              crate.getId(), "1.0.0"))
          .thenReturn(Optional.empty());

      assertThatThrownBy(
              () ->
                  CargoCrateServiceTest.this.cargoCrateService.getCrateVersion(
                      repoInfo, "test-crate", "1.0.0"))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("crateVersionNotFound");
    }
  }

  @Nested
  @DisplayName("incrementDownloadCount()")
  class IncrementDownloadCount {

    @Test
    @DisplayName("increments both crate and meta download counts")
    void incrementsDownloadCounts() {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);

      final var crate = new CargoCrate();
      crate.setId(UUID.randomUUID());
      crate.setTotalDownloads(10L);

      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "test_crate"))
          .thenReturn(Optional.of(crate));

      CargoCrateServiceTest.this.cargoCrateService.incrementDownloadCount(
          repoInfo, "test-crate", "1.0.0");

      assertThat(crate.getTotalDownloads()).isEqualTo(11L);

      verify(CargoCrateServiceTest.this.crateRepository).save(crate);
      verify(CargoCrateServiceTest.this.crateMetaRepository)
          .incrementDownloadCount(crate.getId(), "1.0.0");
    }
  }

  // Helpers

  /** Reads every global author, keyword and category back as a row of its own, like the insert. */
  private void stubGlobalRowsRead() {
    lenient()
        .when(this.authorRepository.findByAuthor(anyString()))
        .thenAnswer(
            i -> {
              final var author = new CargoAuthor();
              author.setId(UUID.randomUUID());
              author.setAuthor(i.getArgument(0));
              return Optional.of(author);
            });
    lenient()
        .when(this.keywordRepository.findByKeyword(anyString()))
        .thenAnswer(
            i -> {
              final var keyword = new CargoKeyword();
              keyword.setId(UUID.randomUUID());
              keyword.setKeyword(i.getArgument(0));
              return Optional.of(keyword);
            });
    lenient()
        .when(this.categoryRepository.findByCategory(anyString()))
        .thenAnswer(
            i -> {
              final var category = new CargoCategory();
              category.setId(UUID.randomUUID());
              category.setCategory(i.getArgument(0));
              return Optional.of(category);
            });
  }

  private BaseRepoInfo<UUID> createRepoInfo(final UUID repoId) {
    return BaseRepoInfo.<UUID>builder().id(repoId).name("test-repo").build();
  }

  private CratePublishRequest createPublishRequest(final String name, final String version) {
    return new CratePublishRequest(
        name,
        version,
        true,
        List.of(),
        null,
        List.of("Author1", "Author2"),
        "Description",
        null,
        null,
        null,
        null,
        List.of("db", "web"),
        List.of("api"),
        "MIT",
        null,
        null,
        null,
        null,
        "checksum",
        null);
  }
}
