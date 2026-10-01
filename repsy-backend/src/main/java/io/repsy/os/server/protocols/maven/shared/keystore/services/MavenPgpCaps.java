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

import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.utils.MavenUploadLimits;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RPS-1796: the abuse caps of a Maven repo's PGP data, and the lock that makes them hold under
 * concurrent requests. They are abuse caps, not quotas: Repsy OS has no tenants or plans.
 *
 * <ul>
 *   <li>registered public keys per repo ({@code repsy.maven.pgp.max-public-keys-per-repo}, 20);
 *   <li>parked (pending) signatures per repo ({@code repsy.maven.pending-signature.max-per-repo},
 *       500);
 *   <li>bytes of one signature file: {@link #MAX_SIGNATURE_BYTES}, enforced for every {@code .asc}
 *       upload by {@code AbstractMavenProtocolFacade} ({@code mavenSignatureTooLarge});
 *   <li>key-server links per repo: bounded by the size of the allow-list, so no check of its own.
 * </ul>
 *
 * <p><b>Lock order.</b> A count-then-insert needs the count and the insert to be one step per repo,
 * so each cap takes a transaction-scoped Postgres advisory lock keyed by the repo and the kind of
 * row, first thing in the transaction that checks and inserts, and holds it to the end of that
 * transaction. No other code takes these locks, and what runs under one takes only row locks of its
 * own table afterwards (the parked row of {@code PendingSignatureService#park}), never a version or
 * repo row lock, so no cycle can form with the order of RPS-1352 and RPS-1789 (parked row, then
 * version row). The repo id is a UUID here, so the lock key is a 64-bit hash of {@code repoId:kind}
 * instead of the Cloud's {@code repoId * 4 + kind}. On the embedded H2 database, which has no
 * advisory locks, the repo row is locked for update instead.
 */
@Component
public class MavenPgpCaps {

  /** The cap on the bytes of one detached signature, the same constant the protocol facade uses. */
  public static final long MAX_SIGNATURE_BYTES = MavenUploadLimits.MAX_SIGNATURE_BYTES;

  private static final int KIND_PUBLIC_KEYS = 1;
  private static final int KIND_PENDING_SIGNATURES = 2;

  @Getter private final int maxPublicKeysPerRepo;
  @Getter private final int maxPendingSignaturesPerRepo;
  private final boolean postgres;

  @PersistenceContext private EntityManager entityManager;

  public MavenPgpCaps(
      @Value("${repsy.maven.pgp.max-public-keys-per-repo:20}") final int maxPublicKeysPerRepo,
      @Value("${repsy.maven.pending-signature.max-per-repo:500}")
          final int maxPendingSignaturesPerRepo,
      @Value("${spring.datasource.url:}") final String datasourceUrl) {

    this.maxPublicKeysPerRepo = maxPublicKeysPerRepo;
    this.maxPendingSignaturesPerRepo = maxPendingSignaturesPerRepo;
    this.postgres = datasourceUrl.startsWith("jdbc:postgresql:");
  }

  /** Serializes the registrations of public keys of one repo until the transaction ends. */
  public void lockPublicKeys(final UUID repoId) {

    this.lock(repoId, KIND_PUBLIC_KEYS);
  }

  /** Serializes the parking of new signatures of one repo until the transaction ends. */
  public void lockPendingSignatures(final UUID repoId) {

    this.lock(repoId, KIND_PENDING_SIGNATURES);
  }

  private void lock(final UUID repoId, final int kind) {

    if (this.postgres) {
      this.entityManager
          .createNativeQuery(
              "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l")
          .setParameter("key", repoId + ":" + kind)
          .getSingleResult();

      return;
    }

    // The embedded H2 database has no advisory locks: lock the repo row for update instead, which
    // serializes the same requests (H2 is the single-node mode, so no lock-order partner is shared
    // with other nodes). The kind is not part of the key, so a key and a signature of one repo
    // wait for each other, which is harmless.
    this.entityManager.find(Repo.class, repoId, LockModeType.PESSIMISTIC_WRITE);
  }
}
