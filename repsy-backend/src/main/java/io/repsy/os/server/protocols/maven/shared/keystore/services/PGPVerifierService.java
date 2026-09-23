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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.bouncycastle.openpgp.PGPUtil.getDecoderStream;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.ParsedPublicKey;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.PublicKeySources;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.util.encoders.Hex;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class PGPVerifierService {

  private static final String KEY_ID_FORMAT = "%016X";
  private static final int PGP_BUFFER_SIZE = 4_096;
  private static final String PRIVATE_KEY_ARMOR_HEADER = "-----BEGIN PGP PRIVATE KEY BLOCK-----";
  private static final int MAX_USER_ID_LENGTH = 255;
  // Order matters (RPS-1194): tried in this order, ubuntu's keyserver first, then openpgp.org.
  private static final @NonNull List<String> KEY_SERVERS =
      List.of(
          "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x%s",
          "https://keys.openpgp.org/pks/lookup?op=get&search=0x%s");

  @Qualifier("pgpVerifierWebClient")
  private final @NonNull WebClient webClient;

  /**
   * Verifies the detached {@code signedFile} signature of {@code file}. The signer's public key is
   * looked up in {@code sources}: its registered armored keys first (RPS-1189), then the repo's
   * custom key-server hosts, then the two hardcoded default key servers.
   *
   * @throws SignatureNotVerifiedException {@code artifactSignatureNotVerified} when {@code
   *     signedFile} is not a parseable OpenPGP signature, does not verify against {@code file}, or
   *     verifies against a key (or its primary key) that is revoked or was expired at the
   *     signature's creation time (RPS-1202)
   * @throws ItemNotFoundException when no source in {@code sources} (nor the default servers) has
   *     the signer's public key
   */
  @SneakyThrows
  public void verify(
      final @NonNull Resource file,
      final @NonNull Resource signedFile,
      final @Nullable PublicKeySources sources) {

    try (final var dataStream = file.getInputStream();
        final var signatureStream = signedFile.getInputStream()) {

      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
      }

      final PGPSignature signature;
      try {
        signature = this.extractSignature(signatureStream);
      } catch (final IOException exception) {
        // RPS-1191: ArmoredInputException ("invalid armor", "crc check failed ..."), an invalid
        // armor header and EOFException all mean that the bytes the client sent are not an OpenPGP
        // signature. Only this parsing is caught: an IOException from a key server's answer or from
        // reading the stored file is infrastructure and stays a 5xx.
        log.warn("signature could not be parsed. Cause: {}", exception.toString());
        throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
      }

      final var matchedKey =
          this.getPublicKey(signature.getKeyID(), sources)
              .orElseThrow(
                  () -> {
                    // The key id is for the log, the client gets a fixed msgId (RPS-1127).
                    log.warn(
                        "no public key found with Id {}",
                        String.format(KEY_ID_FORMAT, signature.getKeyID()));
                    return new ItemNotFoundException("artifactSigningKeyNotFound");
                  });

      this.checkKeyValidity(matchedKey, signature.getCreationTime());

      signature.init(
          new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), matchedKey.signingKey());

      final var buffer = new byte[PGP_BUFFER_SIZE];

      int bytesRead;
      while ((bytesRead = dataStream.read(buffer)) != -1) {
        signature.update(buffer, 0, bytesRead);
      }

      if (!signature.verify()) {
        log.debug("signature verification failed");
        throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
      }
    } catch (final PGPException exception) {
      // The BouncyCastle text ("PGPSignature is not found", ...) is for the log, not for the
      // client.
      log.warn("signature verification failed. Cause: {}", exception.getMessage());
      throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
    }
  }

  private @NonNull Optional<MatchedKey> getPublicKey(
      final long keyId, @Nullable final PublicKeySources sources) {

    final var keyIdHex = String.format(KEY_ID_FORMAT, keyId);

    final var registeredKey = this.findInRegisteredKeys(sources, keyId);
    if (registeredKey.isPresent()) {
      return registeredKey;
    }

    final var customHosts = sources != null ? sources.keyServerHosts() : null;

    final var customKey = this.findInCustomHosts(customHosts, keyIdHex, keyId);
    if (customKey.isPresent()) {
      return customKey;
    }

    for (final var serverTemplate : KEY_SERVERS) {
      final var key = this.fetchKeyFromServer(serverTemplate.formatted(keyIdHex), keyId);

      if (key.isPresent()) {
        return key;
      }
    }

    return Optional.empty();
  }

  /**
   * Tries every registered armored key of {@code sources} for {@code keyId} (RPS-1189), in order,
   * before any key server is asked. A key that fails to parse is a stored row that was valid at
   * registration time, so it is logged and skipped rather than allowed to break the whole lookup.
   */
  private @NonNull Optional<MatchedKey> findInRegisteredKeys(
      @Nullable final PublicKeySources sources, final long keyId) {

    if (sources == null || sources.registeredArmoredKeys().isEmpty()) {
      return Optional.empty();
    }

    for (final var armoredKey : sources.registeredArmoredKeys()) {
      try {
        final var key = this.parsePublicKey(armoredKey, keyId);

        if (key.isPresent()) {
          return key;
        }
      } catch (final PGPException | IOException | RuntimeException exception) {
        // A stored key was validated with parseArmoredPublicKey at registration time and should
        // never fail here, but one corrupted row must not break the lookup for every other key.
        log.warn("a registered public key could not be parsed. Cause: {}", exception.toString());
      }
    }

    return Optional.empty();
  }

  private @NonNull Optional<MatchedKey> findInCustomHosts(
      final @Nullable List<String> hosts, final @NonNull String keyIdHex, final long keyId) {

    if (hosts == null || hosts.isEmpty()) {
      return Optional.empty();
    }

    for (final var host : hosts) {
      final var url = this.buildUrl(host, keyIdHex);
      final var key = this.fetchKeyFromServer(url, keyId);

      if (key.isPresent()) {
        return key;
      }
    }

    return Optional.empty();
  }

  private @NonNull String buildUrl(final @NonNull String host, final @NonNull String keyIdHex) {

    final var normalizedHost = host.trim().replaceFirst("^https?://", "").split("[/?#]", 2)[0];
    return "https://" + normalizedHost + "/pks/lookup?op=get&search=0x" + keyIdHex;
  }

  private @NonNull PGPSignature extractSignature(final @NonNull InputStream signatureStream)
      throws IOException, PGPException {

    final var factory =
        new PGPObjectFactory(getDecoderStream(signatureStream), new JcaKeyFingerprintCalculator());

    Object object;

    while ((object = factory.nextObject()) != null) {
      if (object instanceof @NonNull final PGPSignatureList sl && !sl.isEmpty()) {
        return sl.get(0);
      }

      if (object instanceof @NonNull final PGPSignature s) {
        return s;
      }
    }

    throw new PGPException("PGPSignature is not found");
  }

  private @NonNull Optional<MatchedKey> fetchKeyFromServer(
      final @NonNull String serverUrl, final long keyId) {

    final var keyData =
        this.webClient
            .get()
            .uri(serverUrl)
            .retrieve()
            .bodyToMono(String.class)
            .doOnError(
                error ->
                    log.debug(
                        "Failed to fetch key from server {}: {}", serverUrl, error.getMessage()))
            .onErrorReturn("")
            .block();

    if (keyData == null || !keyData.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
      return Optional.empty();
    }

    try {
      return this.parsePublicKey(keyData, keyId);
    } catch (final PGPException | IOException | RuntimeException exception) {
      // RPS-1194: a key server can answer something that isn't a valid armored key (a proxy error
      // page, a truncated block). That must be treated the same as "this server does not have the
      // key" so the loop tries the next one, not as an infrastructure failure of the whole upload.
      log.warn(
          "the key server at {} answered a key that could not be parsed. Cause: {}",
          serverUrl,
          exception.toString());
      return Optional.empty();
    }
  }

  private @NonNull Optional<MatchedKey> parsePublicKey(
      final @NonNull String keyData, final long keyId) throws PGPException, IOException {

    try (final var ds = getDecoderStream(new ByteArrayInputStream(keyData.getBytes(UTF_8)))) {
      final var collection = new PGPPublicKeyRingCollection(ds, new JcaKeyFingerprintCalculator());
      final var key = collection.getPublicKey(keyId);

      if (key == null) {
        return Optional.empty();
      }

      final var ring = collection.getPublicKeyRing(keyId);
      final var primary = ring != null ? ring.getPublicKey() : key;

      return Optional.of(new MatchedKey(key, primary));
    }
  }

  /**
   * A key that matched a signature's key id, alongside the primary key of the ring it came from
   * (RPS-1202): {@code signingKey} itself when it already is the primary, otherwise the primary key
   * of the ring {@code signingKey} is a subkey of. Both are checked for revocation, since a subkey
   * can be revoked directly or through its primary key being revoked.
   */
  private record MatchedKey(@NonNull PGPPublicKey signingKey, @NonNull PGPPublicKey primaryKey) {}

  /**
   * Refuses a signature made with, or verified against, a key that is revoked or was expired at
   * {@code signatureCreationTime} (RPS-1202). Checked here, once a key has actually been matched by
   * id, regardless of whether it came from a registered key or a key server: an invalid key is a
   * definite refusal (422 {@code artifactSignatureNotVerified}), not a reason to keep looking,
   * since further sources would only ever answer with the same key material for that id.
   *
   * <p>BouncyCastle's {@link PGPPublicKey#hasRevocation()} reports that a revocation signature
   * packet is present, not that the packet is itself a genuine signature by the key's owner; a
   * known limitation, see the PR description.
   */
  private void checkKeyValidity(
      final @NonNull MatchedKey matchedKey, final @NonNull Date signatureCreationTime) {

    this.checkNotRevoked(matchedKey);
    checkNotExpired(matchedKey.signingKey(), signatureCreationTime);
  }

  private void checkNotRevoked(final @NonNull MatchedKey matchedKey) {

    final var signingKey = matchedKey.signingKey();
    final var primaryKey = matchedKey.primaryKey();

    if (signingKey.hasRevocation()) {
      log.warn("the signing key {} carries a revocation", keyIdHex(signingKey));
      throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
    }

    if (primaryKey.getKeyID() != signingKey.getKeyID() && primaryKey.hasRevocation()) {
      log.warn(
          "the primary key {} of signing key {} carries a revocation",
          keyIdHex(primaryKey),
          keyIdHex(signingKey));
      throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
    }
  }

  private static void checkNotExpired(
      final @NonNull PGPPublicKey signingKey, final @NonNull Date signatureCreationTime) {

    final var validSeconds = signingKey.getValidSeconds();
    if (validSeconds <= 0) {
      return; // 0 means the key never expires.
    }

    final var creationTime = signingKey.getCreationTime();
    final var expiryTime = new Date(creationTime.getTime() + validSeconds * 1000L);

    if (signatureCreationTime.before(creationTime) || signatureCreationTime.after(expiryTime)) {
      log.warn(
          "the signing key {} had expired ({} - {}) by the signature's creation time {}",
          keyIdHex(signingKey),
          creationTime,
          expiryTime,
          signatureCreationTime);
      throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
    }
  }

  private static @NonNull String keyIdHex(final @NonNull PGPPublicKey key) {
    return String.format(KEY_ID_FORMAT, key.getKeyID());
  }

  /**
   * Parses an armored OpenPGP public key block a repo owner wants to register directly on its key
   * store (RPS-1189), and reads the identity of its primary key. The block must hold exactly one
   * key (its primary key, plus any subkeys); registering several keys is done with several calls.
   *
   * @throws BadRequestException {@code pgpPublicKeyInvalid} when {@code armored} is not exactly one
   *     armored OpenPGP public key ring (a private key block, plain text, a signature, several
   *     rings, ...)
   */
  public static @NonNull ParsedPublicKey parseArmoredPublicKey(final @NonNull String armored) {

    final var trimmed = armored.trim();

    if (trimmed.startsWith(PRIVATE_KEY_ARMOR_HEADER)) {
      throw new BadRequestException("pgpPublicKeyInvalid");
    }

    ensureBouncyCastleProvider();

    try (final var ds = getDecoderStream(new ByteArrayInputStream(trimmed.getBytes(UTF_8)))) {
      final var collection = new PGPPublicKeyRingCollection(ds, new JcaKeyFingerprintCalculator());
      final var primary = requireSinglePrimaryKey(collection);

      return new ParsedPublicKey(
          String.format(KEY_ID_FORMAT, primary.getKeyID()),
          Hex.toHexString(primary.getFingerprint()).toUpperCase(Locale.ROOT),
          firstUserId(primary));
    } catch (final IOException
        | PGPException
        | IllegalArgumentException
        | ClassCastException exception) {
      log.warn("a submitted public key could not be parsed. Cause: {}", exception.toString());
      throw new BadRequestException("pgpPublicKeyInvalid");
    }
  }

  private static void ensureBouncyCastleProvider() {

    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /**
   * The one ring's primary key {@code parseArmoredPublicKey} requires, or {@code
   * pgpPublicKeyInvalid}.
   */
  private static @NonNull PGPPublicKey requireSinglePrimaryKey(
      final @NonNull PGPPublicKeyRingCollection collection) {

    if (collection.size() != 1) {
      throw new BadRequestException("pgpPublicKeyInvalid");
    }

    return collection.getKeyRings().next().getPublicKey();
  }

  /**
   * The first user id of {@code primary}, truncated defensively, or {@code null} when it has none.
   */
  private static @Nullable String firstUserId(final @NonNull PGPPublicKey primary) {

    final var userIds = primary.getUserIDs();

    if (!userIds.hasNext()) {
      return null;
    }

    final var userId = userIds.next();

    return userId.length() > MAX_USER_ID_LENGTH ? userId.substring(0, MAX_USER_ID_LENGTH) : userId;
  }
}
