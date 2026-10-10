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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.AllowedKeyserverItem;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.generated.model.PgpPublicKeyForm;
import io.repsy.os.generated.model.PgpPublicKeyItem;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactMapper;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.PgpPublicKey;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.PgpPublicKeyRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.events.PgpKeySourcesChangedEvent;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.protocols.maven.shared.keystore.services.PgpVerifierService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEventPublisher;
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

  // RPS-1189: defensive cap on a registered armored key, well above any real OpenPGP key block.
  private static final int MAX_ARMORED_KEY_LENGTH = 65_536;

  private final AllowedKeyserverRepository allowedKeyserverRepository;
  private final KeyStoreRepository keyStoreRepository;
  private final RepoTxService repoTxService;
  private final PgpPublicKeyRepository pgpPublicKeyRepository;
  private final ArtifactMapper artifactConverter;
  private final ApplicationEventPublisher eventPublisher;
  private final PgpVerifierService pgpVerifierService;
  private final MavenPgpCaps caps;

  @Transactional
  public io.repsy.os.generated.model.KeyStoreItem create(
      final RepoInfo repoInfo, final KeyStoreForm form) {

    final var allowedKeyserver =
        this.allowedKeyserverRepository
            .findByIdAndActiveTrue(form.getAllowedKeyserverId())
            .orElseThrow(
                () -> new ItemNotFoundException(ProtocolErrorCodes.ALLOWED_KEYSERVER_NOT_FOUND));

    if (this.hasWellKnownHosts(allowedKeyserver.getHost())) {
      throw new ItemAlreadyExistException(ProtocolErrorCodes.WELLKNOWN_KEY_STORE_HOST);
    }

    if (this.keyStoreRepository.existsByAllowedKeyserverIdAndRepoId(
        allowedKeyserver.getId(), repoInfo.getStorageKey())) {
      throw new ItemAlreadyExistException(ProtocolErrorCodes.KEY_STORE_ALREADY_EXISTS);
    }

    final var repo = this.repoTxService.requireRepo(repoInfo.getStorageKey());

    final var keyStore = new KeyStore();
    keyStore.setRepo(repo);
    keyStore.setAllowedKeyserver(allowedKeyserver);

    final var saved = this.keyStoreRepository.save(keyStore);
    this.publishKeySourcesChanged(repoInfo);

    return io.repsy.os.generated.model.KeyStoreItem.builder()
        .id(saved.getId())
        .allowedKeyserverId(allowedKeyserver.getId())
        .host(allowedKeyserver.getHost())
        .displayName(allowedKeyserver.getDisplayName())
        .build();
  }

  public io.repsy.os.generated.model.KeyStoreItem get(
      final RepoInfo repoInfo, final UUID keyStoreId) {

    final var keyStore =
        this.keyStoreRepository
            .findByIdAndRepoId(keyStoreId, repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.KEY_STORE_NOT_FOUND));

    return io.repsy.os.generated.model.KeyStoreItem.builder()
        .id(keyStore.getId())
        .allowedKeyserverId(keyStore.getAllowedKeyserver().getId())
        .host(keyStore.getAllowedKeyserver().getHost())
        .displayName(keyStore.getAllowedKeyserver().getDisplayName())
        .build();
  }

  @Transactional
  public void delete(final RepoInfo repoInfo, final UUID keyStoreId) {

    final var keyStore =
        this.keyStoreRepository
            .findByIdAndRepoId(keyStoreId, repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.KEY_STORE_NOT_FOUND));

    this.keyStoreRepository.delete(keyStore);
    this.publishKeySourcesChanged(repoInfo);
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

  /**
   * Registers an armored OpenPGP public key directly on a repo's Maven key store (RPS-1189), so it
   * is tried before any key server. Uniqueness is on (repo, fingerprint): the same key can be
   * registered on several repos, but not twice on the same one.
   *
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException {@code pgpPublicKeyInvalid}
   *     when the form's key is too long, or is not exactly one armored OpenPGP public key
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException {@code
   *     pgpPublicKeyLimitReached} when the repo already has {@code
   *     repsy.maven.pgp.max-public-keys-per-repo} keys (RPS-1796)
   * @throws ItemAlreadyExistException {@code pgpPublicKeyAlreadyExists} when the repo already has a
   *     key with the same fingerprint
   * @throws ItemNotFoundException {@code repoNotFound} when the repo has vanished
   */
  @Transactional
  public PgpPublicKeyItem createPublicKey(final RepoInfo repoInfo, final PgpPublicKeyForm form) {

    if (form.getArmoredKey() == null || form.getArmoredKey().length() > MAX_ARMORED_KEY_LENGTH) {
      throw new BadRequestException(ProtocolErrorCodes.PGP_PUBLIC_KEY_INVALID);
    }

    final var parsed = PgpVerifierService.parseArmoredPublicKey(form.getArmoredKey());

    // RPS-1796: the count and the insert are one step per repo, or concurrent registrations
    // would each see room for one more.
    this.caps.lockPublicKeys(repoInfo.getStorageKey());

    if (this.pgpPublicKeyRepository.existsByRepoIdAndFingerprint(
        repoInfo.getStorageKey(), parsed.fingerprintHex())) {
      throw new ItemAlreadyExistException(ProtocolErrorCodes.PGP_PUBLIC_KEY_ALREADY_EXISTS);
    }

    if (this.pgpPublicKeyRepository.countByRepoId(repoInfo.getStorageKey())
        >= this.caps.getMaxPublicKeysPerRepo()) {
      throw new BadRequestException(ProtocolErrorCodes.PGP_PUBLIC_KEY_LIMIT_REACHED);
    }

    final var repo = this.repoTxService.requireRepo(repoInfo.getStorageKey());

    final var pgpPublicKey = new PgpPublicKey();
    pgpPublicKey.setRepo(repo);
    pgpPublicKey.setKeyId(parsed.keyIdHex());
    pgpPublicKey.setFingerprint(parsed.fingerprintHex());
    pgpPublicKey.setUserId(parsed.userId());
    pgpPublicKey.setArmoredKey(form.getArmoredKey());

    final var saved = this.pgpPublicKeyRepository.saveAndFlush(pgpPublicKey);
    this.publishKeySourcesChanged(repoInfo);

    return this.artifactConverter.toPgpPublicKeyItemDto(saved);
  }

  public PgpPublicKeyItem getPublicKey(final RepoInfo repoInfo, final UUID id) {

    return this.pgpPublicKeyRepository
        .findByIdAndRepoId(id, repoInfo.getStorageKey())
        .map(this.artifactConverter::toPgpPublicKeyItemDto)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.PGP_PUBLIC_KEY_NOT_FOUND));
  }

  @Transactional
  public void deletePublicKey(final RepoInfo repoInfo, final UUID id) {

    final var pgpPublicKey =
        this.pgpPublicKeyRepository
            .findByIdAndRepoId(id, repoInfo.getStorageKey())
            .orElseThrow(
                () -> new ItemNotFoundException(ProtocolErrorCodes.PGP_PUBLIC_KEY_NOT_FOUND));

    this.pgpPublicKeyRepository.delete(pgpPublicKey);
    this.pgpVerifierService.evictRegisteredKey(pgpPublicKey.getArmoredKey());
    this.publishKeySourcesChanged(repoInfo);
  }

  public Page<PgpPublicKeyItem> findAllPublicKeys(
      final RepoInfo repoInfo, final Pageable pageable) {

    return this.pgpPublicKeyRepository
        .findAllByRepoId(repoInfo.getStorageKey(), pageable)
        .map(this.artifactConverter::toPgpPublicKeyItemDto);
  }

  /**
   * Everywhere {@link PgpVerifierService#verify} may look for a repo's signers' public keys
   * (RPS-1189): its registered armored keys, then its allowed key-server hosts. When {@code
   * keyServerLookupEnabled} is {@code false} (RPS-1204) the hosts are not even read: no key server
   * is going to be asked.
   */
  public PublicKeySources getPublicKeySources(
      final UUID repoId, final boolean keyServerLookupEnabled) {

    return new PublicKeySources(
        this.pgpPublicKeyRepository.findArmoredKeysByRepoId(repoId),
        keyServerLookupEnabled ? this.findHostsByRepoId(repoId) : List.of(),
        keyServerLookupEnabled);
  }

  public List<AllowedKeyserverItem> findAllActiveKeyservers() {

    return this.allowedKeyserverRepository.findAllByActiveTrueOrderByDisplayNameAscIdAsc().stream()
        .map(
            aks ->
                AllowedKeyserverItem.builder()
                    .id(aks.getId())
                    .host(aks.getHost())
                    .displayName(aks.getDisplayName())
                    .build())
        .toList();
  }

  /**
   * A key or a key-server host of the repo was registered or deleted. Both are per repo (a key is
   * unique on (repo, fingerprint), a key store row belongs to one repo), so exactly one repo is
   * concerned, whatever else has the same key. Handled after this transaction commits: the versions
   * of the repo are recomputed in the background if it verifies every signature (RPS-1334, {@code
   * SignedRecomputeService#onKeySourcesChanged}). Deleting a key does not unsign a version whose
   * signature was verified with it: see {@code ArtifactSignatureService#recompute}.
   */
  private void publishKeySourcesChanged(final RepoInfo repoInfo) {

    this.eventPublisher.publishEvent(new PgpKeySourcesChangedEvent(repoInfo.getStorageKey()));
  }

  private boolean hasWellKnownHosts(final String url) {

    final var noScheme = url.replaceFirst("^https?://", "");
    final var host = noScheme.split(Pattern.quote("/"), -1)[0];

    return host != null && WELL_KNOWN_HOSTS.contains(host.toLowerCase(Locale.ROOT));
  }
}
