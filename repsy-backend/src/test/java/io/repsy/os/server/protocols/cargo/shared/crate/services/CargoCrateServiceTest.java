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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
          .hasMessage("crate `test-crate@1.0.0` already exists in this registry");
    }

    @Test
    @DisplayName("creates new crate, syncs metadata and saves successfully")
    void createsNewCrateAndSyncsMetadata() throws Exception {
      final var repoId = UUID.randomUUID();
      final var repoInfo = CargoCrateServiceTest.this.createRepoInfo(repoId);
      final var request = CargoCrateServiceTest.this.createPublishRequest("my-Crate", "1.0.0");

      when(CargoCrateServiceTest.this.repoRepository.findById(repoId))
          .thenReturn(Optional.of(new Repo()));
      when(CargoCrateServiceTest.this.crateRepository.findByRepoIdAndName(repoId, "my_crate"))
          .thenReturn(Optional.empty());

      when(CargoCrateServiceTest.this.authorRepository.findByAuthor(anyString()))
          .thenReturn(Optional.empty());
      when(CargoCrateServiceTest.this.authorRepository.save(any(CargoAuthor.class)))
          .thenAnswer(i -> i.getArgument(0));

      when(CargoCrateServiceTest.this.keywordRepository.findByKeyword(anyString()))
          .thenReturn(Optional.empty());
      when(CargoCrateServiceTest.this.keywordRepository.save(any(CargoKeyword.class)))
          .thenAnswer(i -> i.getArgument(0));

      when(CargoCrateServiceTest.this.categoryRepository.findByCategory(anyString()))
          .thenReturn(Optional.empty());
      when(CargoCrateServiceTest.this.categoryRepository.save(any(CargoCategory.class)))
          .thenAnswer(i -> i.getArgument(0));

      when(CargoCrateServiceTest.this.crateRepository.save(any(CargoCrate.class)))
          .thenAnswer(i -> i.getArgument(0));

      CargoCrateServiceTest.this.cargoCrateService.publish(repoInfo, request);

      final var crateCaptor = ArgumentCaptor.forClass(CargoCrate.class);
      verify(CargoCrateServiceTest.this.crateRepository, times(2)).save(crateCaptor.capture());

      final var savedCrate = crateCaptor.getValue();
      assertThat(savedCrate.getName()).isEqualTo("my_crate");
      assertThat(savedCrate.getOriginalName()).isEqualTo("my-Crate");
      assertThat(savedCrate.getMaxVersion()).isEqualTo("1.0.0");

      verify(CargoCrateServiceTest.this.crateIndexRepository).save(any(CargoCrateIndex.class));
      verify(CargoCrateServiceTest.this.crateMetaRepository).save(any(CargoCrateMeta.class));
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
                  List.of()));

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

  private BaseRepoInfo<UUID> createRepoInfo(final UUID repoId) {
    return BaseRepoInfo.<UUID>builder().id(repoId).name("test-repo").build();
  }

  private CratePublishRequest createPublishRequest(final String name, final String version) {
    return new CratePublishRequest(
        name,
        version,
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
