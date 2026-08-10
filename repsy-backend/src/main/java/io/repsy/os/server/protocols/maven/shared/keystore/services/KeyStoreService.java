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
import io.repsy.os.generated.model.AllowedKeyserverItem;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class KeyStoreService {

  private static final Set<String> WELL_KNOWN_HOSTS =
      Set.of("keyserver.ubuntu.com", "keys.openpgp.org");

  private final AllowedKeyserverRepository allowedKeyserverRepository;
  private final KeyStoreRepository keyStoreRepository;
  private final RepoRepository repoRepository;
  private final ArtifactConverter artifactConverter;

  @Transactional
  public void create(final RepoInfo repoInfo, final KeyStoreForm form) {

    final var allowedKeyserver =
        this.allowedKeyserverRepository
            .findByIdAndActiveTrue(form.getAllowedKeyserverId())
            .orElseThrow(() -> new ItemNotFoundException("allowedKeyserverNotFound"));

    if (this.hasWellKnownHosts(allowedKeyserver.getHost())) {
      throw new ItemAlreadyExistException("wellknownKeyStoreHost");
    }

    if (this.keyStoreRepository.existsByAllowedKeyserverIdAndRepoId(
        allowedKeyserver.getId(), repoInfo.getStorageKey())) {
      throw new ItemAlreadyExistException("keyStoreAlreadyExists");
    }

    final var repo =
        this.repoRepository
            .findById(repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var keyStore = new KeyStore();
    keyStore.setRepo(repo);
    keyStore.setAllowedKeyserver(allowedKeyserver);

    this.keyStoreRepository.save(keyStore);
  }

  @Transactional
  public void delete(final RepoInfo repoInfo, final UUID keyStoreId) {

    final var keyStore =
        this.keyStoreRepository
            .findByIdAndRepoId(keyStoreId, repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("keyStoreNotFound"));

    this.keyStoreRepository.delete(keyStore);
  }

  public Page<io.repsy.os.generated.model.KeyStoreItem> findAll(
      final RepoInfo repoInfo, final Pageable pageable) {

    return this.keyStoreRepository
        .findAllByRepoId(repoInfo.getStorageKey(), pageable)
        .map(this.artifactConverter::toKeyStoreItemDto);
  }

  public List<String> findHostsByRepoId(final UUID repoId) {

    return this.keyStoreRepository.findAllByRepoId(repoId).stream()
        .map(KeyStoreItem::getHost)
        .toList();
  }

  public List<AllowedKeyserverItem> findAllActiveKeyservers() {

    return this.allowedKeyserverRepository.findAllByActiveTrue().stream()
        .map(
            aks ->
                AllowedKeyserverItem.builder()
                    .id(aks.getId())
                    .host(aks.getHost())
                    .displayName(aks.getDisplayName())
                    .build())
        .toList();
  }

  private boolean hasWellKnownHosts(final String url) {

    final var noScheme = url.replaceFirst("^https?://", "");
    final var host = noScheme.split(Pattern.quote("/"), -1)[0];

    return host != null && WELL_KNOWN_HOSTS.contains(host.toLowerCase(Locale.ROOT));
  }
}
