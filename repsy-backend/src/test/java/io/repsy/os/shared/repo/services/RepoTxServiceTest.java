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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.mappers.RepoConverter;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RPS-1134 and RPS-1158, at the unit level: deterministic coverage of the reserved-name rejection
 * and of the unique-index-violation mapping, without needing a real concurrent race against
 * Postgres (that is {@code RepoNameRaceIT}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepoTxService create/rename")
class RepoTxServiceTest {

  private static final String CONSTRAINT = "ux_repo__name";
  private static final String OTHER_CONSTRAINT = "ux_repo_deploy_token__token";

  @Mock private RepoConverter repoConverter;
  @Mock private RepoRepository repoRepository;

  private RepoTxService service;

  @BeforeEach
  void setUp() {
    this.service = new RepoTxService(this.repoConverter, this.repoRepository);
  }

  private static DataIntegrityViolationException uniqueViolation(final String constraintName) {
    final var message =
        "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"";
    return new DataIntegrityViolationException(
        "could not execute statement", new SQLException(message, "23505"));
  }

  @Nested
  @DisplayName("createRepo")
  class CreateRepo {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"login", "profile", "users", "not-found", "api"})
    @DisplayName("rejects a reserved name before touching the repository (RPS-1158)")
    void rejectsReservedName(final String name) {
      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.createRepo(name, RepoType.MAVEN, false, null))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("repoNameReserved");

      verifyNoInteractions(RepoTxServiceTest.this.repoRepository);
    }

    @Test
    @DisplayName(
        "maps a race lost on the ux_repo__name index to 409 repoExists, not the raw violation"
            + " (RPS-1134)")
    void mapsUniqueViolationToRepoExists() {
      when(RepoTxServiceTest.this.repoRepository.existsByName(anyString())).thenReturn(false);
      when(RepoTxServiceTest.this.repoRepository.saveAndFlush(any(Repo.class)))
          .thenThrow(uniqueViolation(CONSTRAINT));

      assertThatThrownBy(
              () ->
                  RepoTxServiceTest.this.service.createRepo(
                      "race-name", RepoType.MAVEN, false, null))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("repoExists");
    }

    @Test
    @DisplayName("lets a violation of a different constraint propagate unchanged")
    void rethrowsUnrelatedViolation() {
      when(RepoTxServiceTest.this.repoRepository.existsByName(anyString())).thenReturn(false);
      final var violation = uniqueViolation(OTHER_CONSTRAINT);
      when(RepoTxServiceTest.this.repoRepository.saveAndFlush(any(Repo.class)))
          .thenThrow(violation);

      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.createRepo("name", RepoType.MAVEN, false, null))
          .isSameAs(violation);
    }
  }

  @Nested
  @DisplayName("renameRepo")
  class RenameRepo {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"login", "security", "assets"})
    @DisplayName("rejects a reserved target name before looking up the repo (RPS-1158)")
    void rejectsReservedName(final String name) {
      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.renameRepo("current", name, RepoType.MAVEN))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("repoNameReserved");

      verifyNoInteractions(RepoTxServiceTest.this.repoRepository);
    }

    @Test
    @DisplayName(
        "maps a race lost on the ux_repo__name index to 409 repoExists, not the raw violation"
            + " (RPS-1134)")
    void mapsUniqueViolationToRepoExists() {
      final var repo = new Repo();
      repo.setName("current");
      repo.setType(RepoType.MAVEN);

      when(RepoTxServiceTest.this.repoRepository.findByNameAndType("current", RepoType.MAVEN))
          .thenReturn(Optional.of(repo));
      when(RepoTxServiceTest.this.repoRepository.existsByName("race-target")).thenReturn(false);
      when(RepoTxServiceTest.this.repoRepository.saveAndFlush(any(Repo.class)))
          .thenThrow(uniqueViolation(CONSTRAINT));

      assertThatThrownBy(
              () ->
                  RepoTxServiceTest.this.service.renameRepo(
                      "current", "race-target", RepoType.MAVEN))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("repoExists");

      verify(RepoTxServiceTest.this.repoRepository, never()).save(any(Repo.class));
    }
  }

  /**
   * RPS-1210: {@code releases}/{@code snapshots} only apply where the publish path actually reads
   * them (Maven and NuGet). Deterministic coverage of the rejection, without needing Postgres.
   */
  @Nested
  @DisplayName("updateSettings")
  class UpdateSettings {

    private static Repo repoOfType(final RepoType type) {
      final var repo = new Repo();
      repo.setId(UUID.randomUUID());
      repo.setType(type);
      repo.setPrivateRepo(false);
      repo.setAllowOverride(true);
      repo.setReleases(true);
      repo.setSnapshots(true);
      repo.setSecurityScanEnabled(true);
      return repo;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName("rejects a releases change for a type whose publish path never reads it")
    void rejectsReleasesForUnsupportedType(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));

      final var settings = RepoSettingsForm.builder().releases(false).build();

      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.updateSettings(repo.getId(), settings))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("releasesSnapshotsUnsupported");

      verify(RepoTxServiceTest.this.repoRepository, never()).save(any(Repo.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName("rejects a snapshots change for a type whose publish path never reads it")
    void rejectsSnapshotsForUnsupportedType(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));

      final var settings = RepoSettingsForm.builder().snapshots(false).build();

      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.updateSettings(repo.getId(), settings))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("releasesSnapshotsUnsupported");

      verify(RepoTxServiceTest.this.repoRepository, never()).save(any(Repo.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NUGET"})
    @DisplayName("applies releases/snapshots for a type whose publish path reads them")
    void appliesReleasesSnapshotsForSupportedType(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));

      final var settings = RepoSettingsForm.builder().releases(false).snapshots(false).build();

      RepoTxServiceTest.this.service.updateSettings(repo.getId(), settings);

      assertThat(repo.getReleases()).isFalse();
      assertThat(repo.getSnapshots()).isFalse();
      verify(RepoTxServiceTest.this.repoRepository).save(repo);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName(
        "leaves an unsupported type's other fields alone if only releases/snapshots is set")
    void doesNotTouchOtherFieldsWhenRejected(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));

      final var settings =
          RepoSettingsForm.builder().securityScanEnabled(false).releases(false).build();

      assertThatThrownBy(
              () -> RepoTxServiceTest.this.service.updateSettings(repo.getId(), settings))
          .isInstanceOf(BadRequestException.class);

      assertThat(repo.isSecurityScanEnabled()).isTrue();
      verify(RepoTxServiceTest.this.repoRepository, never()).save(any(Repo.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName("an unsupported type still accepts a PUT that leaves releases/snapshots out")
    void appliesOtherFieldsWhenReleasesSnapshotsOmitted(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));

      final var settings = RepoSettingsForm.builder().securityScanEnabled(false).build();

      RepoTxServiceTest.this.service.updateSettings(repo.getId(), settings);

      assertThat(repo.isSecurityScanEnabled()).isFalse();
      verify(RepoTxServiceTest.this.repoRepository).save(repo);
    }
  }

  /** RPS-1210: GET must mirror the same scope as the PUT rejection. */
  @Nested
  @DisplayName("getRepoSettings")
  class GetRepoSettings {

    private static Repo repoOfType(final RepoType type) {
      final var repo = new Repo();
      repo.setId(UUID.randomUUID());
      repo.setType(type);
      repo.setPrivateRepo(false);
      repo.setAllowOverride(true);
      repo.setReleases(true);
      repo.setSnapshots(true);
      repo.setSecurityScanEnabled(true);
      return repo;
    }

    private static RepoInfo toRepoInfo(final Repo repo) {
      return RepoInfo.builder()
          .id(repo.getId())
          .name("repo")
          .type(repo.getType())
          .privateRepo(repo.isPrivateRepo())
          .allowOverride(repo.isAllowOverride())
          .releases(repo.getReleases())
          .snapshots(repo.getSnapshots())
          .searchable(repo.isSearchable())
          .securityScanEnabled(repo.isSecurityScanEnabled())
          .build();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName("nulls out releases/snapshots for a type whose publish path never reads them")
    void nullsReleasesSnapshotsForUnsupportedType(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));
      when(RepoTxServiceTest.this.repoConverter.toRepoInfo(repo)).thenReturn(toRepoInfo(repo));

      final var settings = RepoTxServiceTest.this.service.getRepoSettings(repo.getId());

      assertThat(settings.getReleases()).isNull();
      assertThat(settings.getSnapshots()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NUGET"})
    @DisplayName("exposes releases/snapshots for a type whose publish path reads them")
    void exposesReleasesSnapshotsForSupportedType(final RepoType type) {
      final var repo = repoOfType(type);
      when(RepoTxServiceTest.this.repoRepository.findById(repo.getId()))
          .thenReturn(Optional.of(repo));
      when(RepoTxServiceTest.this.repoConverter.toRepoInfo(repo)).thenReturn(toRepoInfo(repo));

      final var settings = RepoTxServiceTest.this.service.getRepoSettings(repo.getId());

      assertThat(settings.getReleases()).isTrue();
      assertThat(settings.getSnapshots()).isTrue();
    }
  }
}
