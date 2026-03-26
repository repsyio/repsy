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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.time.Duration;
import java.util.List;
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class PGPVerifierService {
  private static final String KEY_ID_FORMAT = "%016X";

  private static final @NonNull Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int PGP_BUFFER_SIZE = 4_096;
  private static final @NonNull Set<String> KEY_SERVERS =
      Set.of(
          "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x%s",
          "https://pgp.mit.edu/pks/lookup?op=get&search=0x%s",
          "https://keys.openpgp.org/pks/lookup?op=get&search=0x%s");

  @Qualifier("pgpVerifierWebClient")
  private final @NonNull WebClient webClient;

  @SneakyThrows
  public void verify(
      final @NonNull Resource file,
      final @NonNull Resource signedFile,
      final @Nullable List<KeyStoreItem> customKeyServers) {

    try (final var dataStream = file.getInputStream();
        final var signatureStream = signedFile.getInputStream()) {

      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
      }

      final var signature = this.extractSignature(signatureStream);

      final var publicKey =
          this.getPublicKey(signature.getKeyID(), customKeyServers)
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
      log.warn("signature verification failed. Cause: {}", exception.getMessage());
      throw new SignatureNotVerifiedException(exception.getMessage());
    }
  }

  private @NonNull Optional<PGPPublicKey> getPublicKey(
      final long keyId, @Nullable final List<KeyStoreItem> customKeyServers)
      throws PGPException, IOException {

    final var keyIdHex = String.format(KEY_ID_FORMAT, keyId);

    // Find and get users' servers
    final var customServerKey = this.findInCustomServers(customKeyServers, keyId);

    if (customServerKey.isPresent()) {
      return customServerKey;
    }

    // Find in pre-defined key stores
    for (final var serverTemplate : KEY_SERVERS) {
      final var key = this.fetchKeyFromServer(serverTemplate.formatted(keyIdHex), keyId);

      if (key.isPresent()) {
        return key;
      }
    }

    return Optional.empty();
  }

  private @NonNull Optional<PGPPublicKey> findInCustomServers(
      final @Nullable List<KeyStoreItem> customKeyServers, final long keyId)
      throws PGPException, IOException {

    if (customKeyServers == null || customKeyServers.isEmpty()) {
      return Optional.empty();
    }

    for (final var serverUrl : customKeyServers) {
      final var url = this.normalizeUrl(serverUrl.getUrl(), String.format(KEY_ID_FORMAT, keyId));

      final var key = this.fetchKeyFromServer(url, keyId);

      if (key.isPresent()) {
        return key;
      }
    }

    return Optional.empty();
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
            .timeout(TIMEOUT)
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

  private @NonNull String normalizeUrl(
      final @NonNull String server, final @NonNull String keyIdHex) {

    if (server.isBlank()) {
      throw new IllegalArgumentException("server blank");
    }

    return UriComponentsBuilder.fromUriString("https://" + server.trim())
        .replacePath("/pks/lookup")
        .queryParam("op", "get")
        .queryParam("search", keyIdHex)
        .build(true)
        .toUriString();
  }
}
