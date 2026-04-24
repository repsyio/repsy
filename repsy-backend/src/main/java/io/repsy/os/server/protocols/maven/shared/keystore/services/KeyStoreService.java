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
package io.repsy.os.server.protocols.maven.shared.keystore.services;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KeyStoreService {

  private static final @NonNull Set<String> WELL_KNOWN_HOSTS =
      Set.of("keyserver.ubuntu.com", "pgp.mit.edu", "keys.openpgp.org");

  private final @NonNull KeyStoreRepository keyStoreRepository;
  private final @NonNull RepoRepository repoRepository;
  private final @NonNull ArtifactConverter artifactConverter;

  public void create(final @NonNull RepoInfo repoInfo, final @NonNull KeyStoreForm form) {

    if (this.hasWellKnownHosts(form.getUrl())) {
      throw new ItemAlreadyExistException("wellknownKeyStoreHost");
    }

    if (this.keyStoreRepository.existsByUrlAndRepoId(form.getUrl(), repoInfo.getStorageKey())) {
      throw new ItemAlreadyExistException("keyStoreUrlAlreadyExists");
    }

    final var repo =
        this.repoRepository
            .findById(repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var keyStore = new KeyStore();

    keyStore.setUrl(form.getUrl());
    keyStore.setRepo(repo);

    this.keyStoreRepository.save(keyStore);
  }

  public void delete(final @NonNull RepoInfo repoInfo, final @NonNull UUID keyStoreId) {

    final var keyStoreOptional =
        this.keyStoreRepository.findByIdAndRepoId(keyStoreId, repoInfo.getStorageKey());

    if (keyStoreOptional.isEmpty()) {
      throw new ItemNotFoundException("keyStoreNotFound");
    }

    final var keyStore = keyStoreOptional.get();

    this.keyStoreRepository.delete(keyStore);
  }

  public @NonNull Page<io.repsy.os.generated.model.KeyStoreItem> findAll(
      final @NonNull RepoInfo repoInfo, final @NonNull Pageable pageable) {

    final var keyStores =
        this.keyStoreRepository.findAllByRepoId(repoInfo.getStorageKey(), pageable);

    if (keyStores.isEmpty()) {
      Page.empty();
    }

    return keyStores.map(this.artifactConverter::toKeyStoreItemDto);
  }

  public @NonNull List<KeyStoreItem> findByRepoId(final UUID repoId) {

    return this.keyStoreRepository.findAllByRepoId(repoId);
  }

  private boolean hasWellKnownHosts(final @NonNull String url) {

    final var noScheme = url.replaceFirst("^https?://", "");

    final var host = noScheme.split(Pattern.quote("/"), -1)[0];

    return host != null && WELL_KNOWN_HOSTS.contains(host.toLowerCase(Locale.ROOT));
  }
}
