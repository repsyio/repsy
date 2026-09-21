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
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates the default repository of a protocol, and repairs it when an earlier attempt stopped
 * half-way.
 *
 * <p>The default repositories are seeded by the per-protocol {@code *AuthListener}s on every {@code
 * UserCreatedEvent}, so seeding must be safe to repeat: a repository that already exists is not an
 * error, and one whose row was committed but whose storage directory was never created (the storage
 * call failed after the row's transaction had committed) is completed by the next event instead of
 * staying broken.
 *
 * <p>This class is deliberately not transactional: the row is committed by {@link
 * RepoTxService#createRepo} before the storage directory is created, exactly as before, and a
 * failing storage call must not roll the row back.
 */
@Slf4j
@Service
@NullMarked
@RequiredArgsConstructor
public class DefaultRepoSeeder {

  private final RepoTxService repoTxService;

  /**
   * Makes sure the default repository {@code name} of {@code type} has a row and a storage
   * directory. Every step is skipped when it is already done.
   *
   * <p>A repo of that name that belongs to another protocol is left alone (its name is taken, and a
   * directory under this protocol would be orphaned), with a warning.
   *
   * @param name the default repository name of the protocol
   * @param type the protocol of the default repository
   * @param storageCreator creates the repository's storage directory from its storage key; it is
   *     called on every run and must therefore be idempotent, like {@code
   *     StorageStrategy#createDirectory}, which must stay so for any future storage backend
   */
  public void seed(final String name, final RepoType type, final Consumer<UUID> storageCreator) {
    final var existing = this.repoTxService.findRepoByName(name);

    if (existing.isPresent() && existing.get().getType() != type) {
      log.warn(
          "Default {} repo '{}' not seeded: the name is used by a {} repo",
          type,
          name,
          existing.get().getType());
      return;
    }

    final var repoInfo = existing.orElseGet(() -> this.createOrFindConcurrent(name, type));

    if (existing.isPresent()) {
      log.debug("Default {} repo '{}' already exists, ensuring its storage directory", type, name);
    }

    storageCreator.accept(repoInfo.getStorageKey());
  }

  /**
   * Creates the row. When another thread wins the check-then-insert race, its committed row is used
   * instead; when there is none after all, the original failure is rethrown.
   */
  private RepoInfo createOrFindConcurrent(final String name, final RepoType type) {
    try {
      return this.repoTxService.createRepo(name, type, true, null);
    } catch (final ItemAlreadyExistException | DataIntegrityViolationException e) {
      log.debug("Default {} repo '{}' was created concurrently", type, name);

      return this.repoTxService.getRepoByNameAndType(name, type).orElseThrow(() -> e);
    }
  }
}
