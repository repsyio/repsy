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
package io.repsy.os.server.protocols.maven.shared.keystore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.AllowedKeyserver;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.support.StubKeyServers;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.keystore.services.PgpVerifierService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

/**
 * RPS-1791 (OS half): the key-server hosts a repo's signatures are looked up on are the active ones
 * only, in a fixed order. A keyserver an admin deactivated must not be contacted, and the host that
 * is tried first must not depend on the query plan.
 */
class KeyStoreHostsIT extends AbstractIT {

  private static final PgpTestKeys SIGNER = PgpTestKeys.generate();

  @Autowired private KeyStoreRepository keyStoreRepository;
  @Autowired private AllowedKeyserverRepository allowedKeyserverRepository;
  @Autowired private KeyStoreService keyStoreService;

  private final List<AllowedKeyserver> createdKeyservers = new CopyOnWriteArrayList<>();

  @AfterEach
  void deleteKeyservers() {
    // key_store rows go with the repo of the fixture, and with the keyserver (ON DELETE CASCADE).
    this.allowedKeyserverRepository.deleteAll(this.createdKeyservers);
    this.createdKeyservers.clear();
  }

  @Test
  void aDeactivatedKeyserverIsNotListedAndIsNeverAsked() {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("ks-hosts"), true, null);
    final var tag = randomTag();

    // Registered out of order, so neither the id order nor the insertion order is the host order.
    final var hostC = "c-" + tag + ".keys.example.test";
    final var hostA = "a-" + tag + ".keys.example.test";
    final var inactive = "b-" + tag + ".keys.example.test";
    final var hostD = "d-" + tag + ".keys.example.test";
    this.register(repo, hostC, true);
    this.register(repo, inactive, false);
    this.register(repo, hostA, true);
    this.register(repo, hostD, true);

    final var listed = this.keyStoreRepository.findAllByRepoId(repo.getId());

    assertThat(listed).extracting(KeyStoreItem::getHost).containsExactly(hostA, hostC, hostD);
    assertThat(this.keyStoreRepository.findAllByRepoId(repo.getId()))
        .extracting(KeyStoreItem::getHost)
        .as("the same order on a repeated lookup")
        .containsExactly(hostA, hostC, hostD);
    assertThat(this.keyStoreService.findHostsByRepoId(repo.getId()))
        .containsExactly(hostA, hostC, hostD);

    // The verifier asks the hosts of the lookup, and the stub records every request it gets.
    final var requested = new CopyOnWriteArrayList<String>();
    final var verifier =
        new PgpVerifierService(
            StubKeyServers.answering(
                uri -> {
                  requested.add(uri.toString());

                  return StubKeyServers.notFound();
                }));
    final var file = "content".getBytes(UTF_8);
    final var sources = this.keyStoreService.getPublicKeySources(repo.getId(), true);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    new ByteArrayResource(file),
                    new ByteArrayResource(SIGNER.detachedSignature(file).getBytes(UTF_8)),
                    sources))
        .isInstanceOf(ItemNotFoundException.class);

    assertThat(requested).noneMatch(url -> url.contains(inactive));
    assertThat(requested.subList(0, 3))
        .as("the active hosts are asked first, in host order")
        .satisfiesExactly(
            url -> assertThat(url).contains(hostA),
            url -> assertThat(url).contains(hostC),
            url -> assertThat(url).contains(hostD));
  }

  private void register(final Repo repo, final String host, final boolean active) {
    final var keyserver = new AllowedKeyserver();
    keyserver.setHost(host);
    keyserver.setDisplayName(host);
    keyserver.setActive(active);
    final var saved = this.allowedKeyserverRepository.save(keyserver);
    this.createdKeyservers.add(saved);

    final var keyStore = new KeyStore();
    keyStore.setRepo(repo);
    keyStore.setAllowedKeyserver(saved);
    this.keyStoreRepository.save(keyStore);
  }
}
