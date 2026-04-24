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

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.RepoListInfo;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.generated.model.RepoSettingsInfo;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.mappers.RepoConverter;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.utils.RepoUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepoTxService {

  private final @NonNull RepoConverter repoConverter;
  private final @NonNull RepoRepository repoRepository;

  @Transactional
  public @NonNull RepoInfo createRepo(
      final @NonNull String name,
      final @NonNull RepoType repoType,
      final boolean privateRepo,
      final @Nullable String description) {

    RepoUtils.validateRepoName(name);

    this.checkIfRepoExists(name);

    final var repo = new Repo();
    repo.setName(name);
    repo.setPrivateRepo(privateRepo);
    repo.setDescription(description);
    repo.setAllowOverride(true);
    repo.setSnapshots(true);
    repo.setReleases(true);
    repo.setType(repoType);

    this.repoRepository.save(repo);
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

  public @NonNull Optional<RepoInfo> getRepoByNameAndType(
      final @NonNull String name, final @NonNull RepoType type) {
    return this.repoRepository.findByNameAndType(name, type).map(this::mapToRepoInfo);
  }

  public @NonNull Repo getRepoEntity(final @NonNull UUID repoId) {
    return this.findRepoById(repoId);
  }

  @Transactional
  public void updateSettings(final @NonNull UUID repoId, final @NonNull RepoSettingsForm settings) {
    final var repo = this.findRepoById(repoId);
    repo.setPrivateRepo(settings.getPrivateRepo());
    repo.setAllowOverride(settings.getAllowOverride());
    repo.setReleases(settings.getReleases());
    repo.setSnapshots(settings.getSnapshots());
    this.repoRepository.save(repo);
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

    RepoUtils.validateRepoName(newRepoName);

    final var repo =
        this.findRepoOrThrowException(this.repoRepository.findByNameAndType(repoName, repoType));

    this.checkIfRepoExists(newRepoName);

    repo.setName(newRepoName);
    this.repoRepository.save(repo);
  }

  @Transactional
  public void updateDescription(final @NonNull UUID repoId, final @Nullable String description) {
    final var repo = this.findRepoById(repoId);
    repo.setDescription(description);
    this.repoRepository.save(repo);
  }

  public @NonNull RepoSettingsInfo getRepoSettings(final @NonNull UUID repoId) {
    final var repoInfo = this.getRepo(repoId);
    return RepoSettingsInfo.builder()
        .privateRepo(repoInfo.isPrivateRepo())
        .releases(repoInfo.getReleases())
        .snapshots(repoInfo.getSnapshots())
        .searchable(repoInfo.isSearchable())
        .allowOverride(repoInfo.isAllowOverride())
        .build();
  }

  public List<@NonNull RepoListInfo> findAllByRepoType(final @NonNull RepoType repoType) {
    return this.repoRepository.findAllByType(repoType).stream()
        .map(this::mapToRepoListInfo)
        .toList();
  }

  public long getRepoCount(final @NonNull RepoType repoType) {
    return this.repoRepository.countAllByType(repoType);
  }

  public void updateDiskUsage(final @NonNull UUID repoId, final long diskUsageDiff) {
    this.repoRepository.updateDiskUsage(repoId, diskUsageDiff);
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
}
