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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.generated.model.PgpPublicKeyForm;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.AllowedKeyserver;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.PgpPublicKey;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.PgpPublicKeyRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.events.PgpKeySourcesChangedEvent;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Every change of where a repo looks for the keys of its signers publishes the event that
 * recomputes {@code signed} (RPS-1334), and one that is refused publishes nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeyStoreService key-sources event (RPS-1334)")
class KeyStoreServiceTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();

  @Mock AllowedKeyserverRepository allowedKeyserverRepository;
  @Mock KeyStoreRepository keyStoreRepository;
  @Mock RepoRepository repoRepository;
  @Mock PgpPublicKeyRepository pgpPublicKeyRepository;
  @Mock ArtifactConverter artifactConverter;
  @Mock ApplicationEventPublisher eventPublisher;

  private KeyStoreService service;
  private final UUID repoId = UUID.randomUUID();
  private final RepoInfo repoInfo = RepoInfo.builder().storageKey(this.repoId).name("repo").build();
  private final PgpKeySourcesChangedEvent event = new PgpKeySourcesChangedEvent(this.repoId);

  @BeforeEach
  void setUp() {
    this.service =
        new KeyStoreService(
            this.allowedKeyserverRepository,
            this.keyStoreRepository,
            this.repoRepository,
            this.pgpPublicKeyRepository,
            this.artifactConverter,
            this.eventPublisher);
  }

  private PgpPublicKeyForm keyForm() {
    return PgpPublicKeyForm.builder().armoredKey(KEYS.armoredPublicKey()).build();
  }

  @Test
  @DisplayName("registering a public key publishes the event for its repo")
  void registeringAKeyPublishes() {
    when(this.repoRepository.findById(this.repoId)).thenReturn(Optional.of(new Repo()));
    when(this.pgpPublicKeyRepository.saveAndFlush(any(PgpPublicKey.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    this.service.createPublicKey(this.repoInfo, this.keyForm());

    verify(this.eventPublisher).publishEvent(this.event);
  }

  @Test
  @DisplayName("a public key that is refused publishes nothing")
  void aRefusedKeyPublishesNothing() {
    when(this.pgpPublicKeyRepository.existsByRepoIdAndFingerprint(any(), any())).thenReturn(true);

    assertThatThrownBy(() -> this.service.createPublicKey(this.repoInfo, this.keyForm()))
        .isInstanceOf(ItemAlreadyExistException.class);

    verify(this.eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  @DisplayName("deleting a public key publishes the event for its repo")
  void deletingAKeyPublishes() {
    final var id = UUID.randomUUID();
    when(this.pgpPublicKeyRepository.findByIdAndRepoId(id, this.repoId))
        .thenReturn(Optional.of(new PgpPublicKey()));

    this.service.deletePublicKey(this.repoInfo, id);

    verify(this.eventPublisher).publishEvent(this.event);
  }

  @Test
  @DisplayName("adding a key-server host publishes the event for its repo")
  void addingAHostPublishes() {
    final var allowed = mock(AllowedKeyserver.class);
    final var allowedId = UUID.randomUUID();
    when(allowed.getHost()).thenReturn("keys.example.org");
    when(allowed.getId()).thenReturn(allowedId);
    when(this.allowedKeyserverRepository.findByIdAndActiveTrue(allowedId))
        .thenReturn(Optional.of(allowed));
    when(this.repoRepository.findById(this.repoId)).thenReturn(Optional.of(new Repo()));

    this.service.create(
        this.repoInfo, KeyStoreForm.builder().allowedKeyserverId(allowedId).build());

    verify(this.eventPublisher).publishEvent(this.event);
  }

  @Test
  @DisplayName("removing a key-server host publishes the event for its repo")
  void removingAHostPublishes() {
    final var id = UUID.randomUUID();
    when(this.keyStoreRepository.findByIdAndRepoId(id, this.repoId))
        .thenReturn(Optional.of(new KeyStore()));

    this.service.delete(this.repoInfo, id);

    verify(this.eventPublisher).publishEvent(this.event);
  }
}
