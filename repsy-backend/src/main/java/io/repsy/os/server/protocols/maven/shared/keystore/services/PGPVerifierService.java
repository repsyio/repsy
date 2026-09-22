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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
  private static final @NonNull Set<String> KEY_SERVERS =
      Set.of(
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
   *     signedFile} is not a parseable OpenPGP signature, or does not verify against {@code file}
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

      final var publicKey =
          this.getPublicKey(signature.getKeyID(), sources)
              .orElseThrow(
                  () ->
                      new ItemNotFoundException(
                          "no public key found with Id %s"
                              .formatted(String.format(KEY_ID_FORMAT, signature.getKeyID()))));

      signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), publicKey);

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

  private @NonNull Optional<PGPPublicKey> getPublicKey(
      final long keyId, @Nullable final PublicKeySources sources) throws PGPException, IOException {

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
  private @NonNull Optional<PGPPublicKey> findInRegisteredKeys(
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

  private @NonNull Optional<PGPPublicKey> findInCustomHosts(
      final @Nullable List<String> hosts, final @NonNull String keyIdHex, final long keyId)
      throws PGPException, IOException {

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

  private @NonNull Optional<PGPPublicKey> fetchKeyFromServer(
      final @NonNull String serverUrl, final long keyId) throws PGPException, IOException {

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

    if (keyData != null && keyData.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
      return this.parsePublicKey(keyData, keyId);
    }

    return Optional.empty();
  }

  private @NonNull Optional<PGPPublicKey> parsePublicKey(
      final @NonNull String keyData, final long keyId) throws PGPException, IOException {

    try (final var ds = getDecoderStream(new ByteArrayInputStream(keyData.getBytes(UTF_8)))) {
      final var collection = new PGPPublicKeyRingCollection(ds, new JcaKeyFingerprintCalculator());

      return Optional.ofNullable(collection.getPublicKey(keyId));
    }
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
