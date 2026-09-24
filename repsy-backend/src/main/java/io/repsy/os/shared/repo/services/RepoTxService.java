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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.RepoListInfo;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.generated.model.RepoSettingsInfo;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.mappers.RepoConverter;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.utils.RepoUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepoTxService {

  /**
   * Repo types whose publish path actually consults the {@code releases}/{@code snapshots} settings
   * (RPS-1210): Maven ({@link
   * io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl}) and NuGet
   * ({@code NuGetPackageUtils}) gate a version's upload on them. Every other repo type stores and
   * exposes the fields but never reads them, which silently misleads an operator into thinking they
   * restricted publishing when they did not.
   */
  private static final Set<RepoType> RELEASES_SNAPSHOTS_SUPPORTED_TYPES =
      EnumSet.of(RepoType.MAVEN, RepoType.NUGET);

  /**
   * Repo types whose upload path verifies PGP signatures (RPS-1188, RPS-1204): only Maven does. The
   * settings are refused for any other type, like {@code releases}/{@code snapshots} above.
   */
  private static final Set<RepoType> PGP_SETTINGS_SUPPORTED_TYPES = EnumSet.of(RepoType.MAVEN);

  private final @NonNull RepoConverter repoConverter;
  private final @NonNull RepoRepository repoRepository;

  @Transactional
  public @NonNull RepoInfo createRepo(
      final @NonNull String name,
      final @NonNull RepoType repoType,
      final boolean privateRepo,
      final @Nullable String description) {

    RepoUtils.validateNewRepoName(name);

    this.checkIfRepoExists(name);

    final var repo = new Repo();
    repo.setName(name);
    repo.setPrivateRepo(privateRepo);
    repo.setDescription(description);
    repo.setAllowOverride(true);
    repo.setSnapshots(true);
    repo.setReleases(true);
    repo.setSecurityScanEnabled(true);
    repo.setType(repoType);

    this.saveOrThrowIfNameTaken(repo);
    return this.mapToRepoInfo(repo);
  }

  public @NonNull RepoInfo getRepo(final @NonNull String name, final @NonNull RepoType type) {
    return this.mapToRepoInfo(
        this.findRepoOrThrowException(this.repoRepository.findByNameAndType(name, type)));
  }

  public @NonNull RepoInfo getRepo(final @NonNull UUID repoId) {
    return this.mapToRepoInfo(this.findRepoById(repoId));
  }

  public @NonNull RepoInfo getRepoByName(final @NonNull String name) {
    return this.mapToRepoInfo(this.findRepoOrThrowException(this.repoRepository.findByName(name)));
  }

  public @NonNull Optional<RepoInfo> findRepoByName(final @NonNull String name) {
    return this.repoRepository.findByName(name).map(this::mapToRepoInfo);
  }

  public @NonNull Optional<RepoInfo> getRepoByNameAndType(
      final @NonNull String name, final @NonNull RepoType type) {
    return this.repoRepository.findByNameAndType(name, type).map(this::mapToRepoInfo);
  }

  public @NonNull Repo getRepoEntity(final @NonNull UUID repoId) {
    return this.findRepoById(repoId);
  }

  /**
   * Applies the given settings; a field that is null (absent from the request) is left as it is.
   *
   * @throws BadRequestException {@code releasesSnapshotsUnsupported} if {@code releases} or {@code
   *     snapshots} is present for a repo type whose publish path does not consult them (RPS-1210)
   * @throws BadRequestException {@code pgpSettingsUnsupported} if {@code
   *     pgpVerifyAllSignaturesEnabled} or {@code pgpKeyServerLookupEnabled} is present for a repo
   *     that is not a Maven one (RPS-1188, RPS-1204)
   */
  @Transactional
  public void updateSettings(final @NonNull UUID repoId, final @NonNull RepoSettingsForm settings) {

    final var repo = this.findRepoById(repoId);

    this.rejectReleasesSnapshotsForUnsupportedType(repo, settings);
    this.rejectPgpSettingsForUnsupportedType(repo, settings);

    applyIfPresent(settings.getPrivateRepo(), repo::setPrivateRepo);
    applyIfPresent(settings.getAllowOverride(), repo::setAllowOverride);
    applyIfPresent(settings.getReleases(), repo::setReleases);
    applyIfPresent(settings.getSnapshots(), repo::setSnapshots);
    applyIfPresent(settings.getSecurityScanEnabled(), repo::setSecurityScanEnabled);
    applyIfPresent(
        settings.getPgpVerifyAllSignaturesEnabled(), repo::setPgpVerifyAllSignaturesEnabled);
    applyIfPresent(settings.getPgpKeyServerLookupEnabled(), repo::setPgpKeyServerLookupEnabled);

    this.repoRepository.save(repo);
  }

  /** Hands {@code value} to {@code setter} unless it is absent from the request. */
  private static void applyIfPresent(
      final @Nullable Boolean value, final @NonNull Consumer<Boolean> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }

  @Transactional
  public void deleteRepo(final @NonNull UUID repoId) {
    this.repoRepository.delete(this.findRepoById(repoId));
  }

  @Transactional
  public void renameRepo(
      final @NonNull String repoName,
      final @NonNull String newRepoName,
      final @NonNull RepoType repoType) {

    RepoUtils.validateNewRepoName(newRepoName);

    final var repo =
        this.findRepoOrThrowException(this.repoRepository.findByNameAndType(repoName, repoType));

    this.checkIfRepoExists(newRepoName);

    repo.setName(newRepoName);
    this.saveOrThrowIfNameTaken(repo);
  }

  @Transactional
  public void updateDescription(final @NonNull UUID repoId, final @Nullable String description) {
    final var repo = this.findRepoById(repoId);
    repo.setDescription(description);
    this.repoRepository.save(repo);
  }

  public @NonNull RepoSettingsInfo getRepoSettings(final @NonNull UUID repoId) {
    final var repoInfo = this.getRepo(repoId);
    final var supportsReleasesSnapshots =
        RELEASES_SNAPSHOTS_SUPPORTED_TYPES.contains(repoInfo.getType());
    final var supportsPgp = PGP_SETTINGS_SUPPORTED_TYPES.contains(repoInfo.getType());
    return RepoSettingsInfo.builder()
        .privateRepo(repoInfo.isPrivateRepo())
        .releases(supportsReleasesSnapshots ? repoInfo.getReleases() : null)
        .snapshots(supportsReleasesSnapshots ? repoInfo.getSnapshots() : null)
        .searchable(repoInfo.isSearchable())
        .allowOverride(repoInfo.isAllowOverride())
        .securityScanEnabled(repoInfo.isSecurityScanEnabled())
        .pgpVerifyAllSignaturesEnabled(
            supportsPgp ? repoInfo.isPgpVerifyAllSignaturesEnabled() : null)
        .pgpKeyServerLookupEnabled(supportsPgp ? repoInfo.isPgpKeyServerLookupEnabled() : null)
        .build();
  }

  public List<@NonNull RepoListInfo> findAllByRepoType(final @NonNull RepoType repoType) {
    return this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(repoType).stream()
        .map(this::mapToRepoListInfo)
        .toList();
  }

  public long getRepoCount(final @NonNull RepoType repoType) {
    return this.repoRepository.countAllByType(repoType);
  }

  public @NonNull List<String> getAllRepoNames() {
    return this.repoRepository.findAllRepoNames();
  }

  /**
   * Adds {@code diskUsageDiff} to the repo's disk usage.
   *
   * @return whether the repo still existed, {@code false} when nothing was updated
   */
  public boolean updateDiskUsage(final @NonNull UUID repoId, final long diskUsageDiff) {
    return this.repoRepository.updateDiskUsage(repoId, diskUsageDiff) > 0;
  }

  /**
   * Reads the repo's disk usage and locks its row until the surrounding transaction ends.
   *
   * @return the usage, empty when the repo no longer exists
   */
  public @NonNull Optional<Long> findDiskUsageForUpdate(final @NonNull UUID repoId) {
    return this.repoRepository.findDiskUsageByIdForUpdate(repoId);
  }

  private void rejectReleasesSnapshotsForUnsupportedType(
      final @NonNull Repo repo, final @NonNull RepoSettingsForm settings) {
    final var touchesReleasesSnapshots =
        settings.getReleases() != null || settings.getSnapshots() != null;
    if (touchesReleasesSnapshots && !RELEASES_SNAPSHOTS_SUPPORTED_TYPES.contains(repo.getType())) {
      throw new BadRequestException("releasesSnapshotsUnsupported");
    }
  }

  private void rejectPgpSettingsForUnsupportedType(
      final @NonNull Repo repo, final @NonNull RepoSettingsForm settings) {
    final var touchesPgp =
        settings.getPgpVerifyAllSignaturesEnabled() != null
            || settings.getPgpKeyServerLookupEnabled() != null;
    if (touchesPgp && !PGP_SETTINGS_SUPPORTED_TYPES.contains(repo.getType())) {
      throw new BadRequestException("pgpSettingsUnsupported");
    }
  }

  private @NonNull Repo findRepoOrThrowException(final @NonNull Optional<Repo> repoOptional) {
    return repoOptional.orElseThrow(() -> new ItemNotFoundException("repoNotFound"));
  }

  private @NonNull Repo findRepoById(final @NonNull UUID repoId) {
    return this.findRepoOrThrowException(this.repoRepository.findById(repoId));
  }

  private @NonNull RepoInfo mapToRepoInfo(final @NonNull Repo repo) {
    return Objects.requireNonNull(this.repoConverter.toRepoInfo(repo));
  }

  private @NonNull RepoListInfo mapToRepoListInfo(final @NonNull Repo repo) {
    return Objects.requireNonNull(this.repoConverter.toRepoListInfo(repo));
  }

  private void checkIfRepoExists(final @NonNull String name) {
    if (this.repoRepository.existsByName(name)) {
      throw new ItemAlreadyExistException("repoExists");
    }
  }

  /**
   * Saves the repo, flushing so a unique-index violation surfaces here rather than at the
   * transaction's commit. {@link #checkIfRepoExists} is a check-then-write and cannot close a race
   * between two callers choosing the same free name: the loser still reaches the {@code
   * ux_repo__name} index, but gets the specific {@code repoExists} 409 instead of falling through
   * to the generic {@code DataIntegrityViolationException} handling in {@code ErrorHandler} (RPS-
   * 1134).
   */
  private void saveOrThrowIfNameTaken(final @NonNull Repo repo) {
    try {
      this.repoRepository.saveAndFlush(repo);
    } catch (final DataIntegrityViolationException e) {
      if (ConstraintViolations.violatesConstraint(e, "ux_repo__name")) {
        throw new ItemAlreadyExistException("repoExists");
      }
      throw e;
    }
  }
}
