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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Ticker;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.os.server.protocols.maven.shared.keystore.support.StubKeyServers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The detached-signature check of a Maven upload, with real OpenPGP signatures and a key server
 * that is an in-memory exchange function (no network). RPS-1186: a refused signature answers the
 * fixed {@code artifactSignatureNotVerified} id, not the BouncyCastle text. RPS-1191: so does a
 * body that is not an OpenPGP signature at all (invalid armor, a bad CRC, binary garbage), which
 * BouncyCastle reports as an {@code IOException} and which used to answer 500. RPS-1189: a repo's
 * registered public keys (see {@link PublicKeySources}) are tried before any key server. RPS-1194:
 * a key server answering a corrupt key block is skipped the same way, trying the next one instead
 * of failing the whole lookup. RPS-1202: a key that is revoked, or that was expired at the
 * signature's creation time, is refused the same way a bad signature is, whether the key came from
 * a registered key or a key server.
 */
@DisplayName("PgpVerifierService")
class PgpVerifierServiceTest {

  private static final byte[] POM = "<project>lib 1.0</project>".getBytes(UTF_8);
  private static final byte[] OTHER_POM = "<project>lib 2.0</project>".getBytes(UTF_8);
  private static final String NOT_VERIFIED = "artifactSignatureNotVerified";
  private static final String INVALID_KEY = "pgpPublicKeyInvalid";

  private static PgpTestKeys keys;

  private final List<URI> asked = new ArrayList<>();

  @BeforeAll
  static void generateKey() {
    keys = PgpTestKeys.generate();
  }

  private PgpVerifierService serviceAnswering(final Function<URI, ClientHttpResponse> answers) {
    return new PgpVerifierService(
        StubKeyServers.answering(
            uri -> {
              this.asked.add(uri);

              return answers.apply(uri);
            }));
  }

  private PgpVerifierService serviceWithTheKey() {
    return this.serviceAnswering(uri -> keyResponse());
  }

  private static ClientHttpResponse keyResponse() {
    return StubKeyServers.ok(keys.armoredPublicKey());
  }

  private static ClientHttpResponse notFound() {
    return StubKeyServers.notFound();
  }

  private static Resource resource(final String text) {
    return new ByteArrayResource(text.getBytes(UTF_8));
  }

  /** No registered keys, no custom hosts: only the (faked) default key servers are asked. */
  private static PublicKeySources noRegisteredKeys() {
    return PublicKeySources.none();
  }

  private static PublicKeySources hosts(final String... hosts) {
    return new PublicKeySources(List.of(), List.of(hosts), true);
  }

  private static PublicKeySources registeredKeys(final String... armoredKeys) {
    return new PublicKeySources(List.of(armoredKeys), List.of(), true);
  }

  private static PublicKeySources lookupOff(final String... armoredKeys) {
    return new PublicKeySources(List.of(armoredKeys), List.of("keys.acme.com"), false);
  }

  /** A clock the test moves by hand, to expire cached key blocks without waiting. */
  private static final class ManualTicker extends Ticker {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
      return this.nanos.get();
    }

    void advance(final Duration duration) {
      this.nanos.addAndGet(duration.toNanos());
    }
  }

  @Test
  @DisplayName(
      "with the lookup off a registered key still verifies and no server is asked (RPS-1204)")
  void lookupOffStillVerifiesARegisteredKey() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM), signature, lookupOff(keys.armoredPublicKey())))
        .doesNotThrowAnyException();

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("with the lookup off an unregistered key is refused without asking any server")
  void lookupOffRefusesAnUnregisteredKeyWithoutAskingAServer() {
    final var signature = resource(keys.detachedSignature(POM));
    final var service = this.serviceWithTheKey();

    assertThatThrownBy(() -> service.verify(new ByteArrayResource(POM), signature, lookupOff()))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactSigningKeyNotRegistered");

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("with the lookup on an unregistered key is asked for on the servers, as before")
  void lookupOnAsksTheServersForAnUnregisteredKey() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .doesNotThrowAnyException();

    assertThat(this.asked).isNotEmpty();
  }

  @Test
  @DisplayName("a key block a server answered is asked for once, however many signatures use it")
  void aKeyBlockIsCachedAcrossVerifications() {
    final var signature = resource(keys.detachedSignature(POM));
    final var service = this.serviceWithTheKey();

    for (int i = 0; i < 3; i++) {
      service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys());
    }

    assertThat(this.asked).hasSize(1);
  }

  @Test
  @DisplayName("a cached key block is asked for again after ten minutes")
  void aCachedKeyBlockExpires() {
    final var signature = resource(keys.detachedSignature(POM));
    final var ticker = new ManualTicker();
    final var service =
        new PgpVerifierService(
            StubKeyServers.answering(
                uri -> {
                  this.asked.add(uri);

                  return keyResponse();
                }),
            ticker);

    service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys());
    ticker.advance(Duration.ofMinutes(9));
    service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys());

    assertThat(this.asked).hasSize(1);

    ticker.advance(Duration.ofMinutes(2));
    service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys());

    assertThat(this.asked).hasSize(2);
  }

  @Test
  @DisplayName("a server that did not have the key is asked again: a miss is not cached")
  void aMissIsNotCached() {
    final var signature = resource(keys.detachedSignature(POM));
    final var service = this.serviceAnswering(uri -> notFound());

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () -> service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("artifactSigningKeyNotFound");
    }

    // Two default servers per verification, none of them cached.
    assertThat(this.asked).hasSize(4);
  }

  @Test
  @DisplayName("reads the id of the key that made a signature without asking any server (RPS-1188)")
  void readsTheSignerKeyId() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThat(this.serviceWithTheKey().readSignerKeyId(signature))
        .isEqualTo("%016X".formatted(keys.keyId()));
    assertThat(this.asked).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not a signature at all",
        "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n"
      })
  @DisplayName("refuses to read the key id of what is not a signature (RPS-1188)")
  void refusesToReadTheKeyIdOfGarbage(final String garbage) {
    final var service = this.serviceWithTheKey();
    final var resource = resource(garbage);

    assertThatThrownBy(() -> service.readSignerKeyId(resource))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("accepts the detached signature of the stored file")
  void verifiesADetachedSignatureOfTheStoredFile() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a signature made over other bytes with the fixed message id")
  void refusesASignatureOfOtherBytes() {
    final var signature = resource(keys.detachedSignature(OTHER_POM));

    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("refuses an empty .asc, and never asks a key server about it")
  void refusesAnEmptyAsc() {
    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(
                        new ByteArrayResource(POM),
                        new ByteArrayResource(new byte[0]),
                        noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("refuses an armored block that holds no signature packet, with the fixed id")
  void refusesAnAscWithoutASignaturePacket() {
    final var publicKeyInsteadOfSignature = resource(keys.armoredPublicKey());

    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(
                        new ByteArrayResource(POM),
                        publicKeyInsteadOfSignature,
                        noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  /** Asserts that the check refuses these bytes as {@code artifactSignatureNotVerified}. */
  private void assertRefusedWithoutAskingAKeyServer(final Resource signature) {
    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  /** The lines of an armored signature, without their line endings. */
  private static List<String> armorLines(final String armored) {
    return new ArrayList<>(List.of(armored.split("\r?\n")));
  }

  /** The index of the {@code =XXXX} CRC line of an armored block. */
  private static int crcLineIndex(final List<String> lines) {
    for (var i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).startsWith("=")) {
        return i;
      }
    }

    throw new IllegalStateException("no CRC line in " + lines);
  }

  @ParameterizedTest(name = "line ending {0}")
  @ValueSource(strings = {"\n", "\r\n"})
  @DisplayName("refuses an armor with no packet in it (RPS-1191: it used to answer 500)")
  void refusesArmorWithoutAPacket(final String eol) {
    this.assertRefusedWithoutAskingAKeyServer(
        resource(
            "-----BEGIN PGP SIGNATURE-----" + eol + eol + "-----END PGP SIGNATURE-----" + eol));
  }

  @Test
  @DisplayName("refuses an armor with an invalid header line")
  void refusesAnArmorWithAnInvalidHeaderLine() {
    this.assertRefusedWithoutAskingAKeyServer(
        resource("-----BEGIN PGP SIGNATURE-----\nabc\n\n-----END PGP SIGNATURE-----\n"));
  }

  @Test
  @DisplayName("refuses text that is not armored at all")
  void refusesPlainText() {
    this.assertRefusedWithoutAskingAKeyServer(resource("hello"));
  }

  @Test
  @DisplayName("refuses an armored signature that ends early, with the fixed id")
  void refusesATruncatedArmoredSignature() {
    final var lines = armorLines(keys.detachedSignature(POM));
    final var crc = crcLineIndex(lines);
    lines.subList(crc - 3, crc).clear();

    this.assertRefusedWithoutAskingAKeyServer(resource(String.join("\n", lines) + "\n"));
  }

  @Test
  @DisplayName("refuses an armored signature whose CRC does not match, with the fixed id")
  void refusesACorruptedArmoredSignature() {
    final var lines = armorLines(keys.detachedSignature(POM));
    final var firstData = lines.indexOf("") + 1;
    final var line = lines.get(firstData);
    final var flipped = line.charAt(40) == 'A' ? 'B' : 'A';
    lines.set(firstData, line.substring(0, 40) + flipped + line.substring(41));

    this.assertRefusedWithoutAskingAKeyServer(resource(String.join("\n", lines) + "\n"));
  }

  @Test
  @DisplayName("refuses binary garbage")
  void refusesBinaryGarbage() {
    this.assertRefusedWithoutAskingAKeyServer(new ByteArrayResource(new byte[] {-1, -1}));
  }

  @Test
  @DisplayName("refuses an armored compressed-data packet, which holds no signature")
  void refusesACompressedDataPacket() throws IOException {
    // Not an IOException: this one was already a PGPException (422) before RPS-1191; pinned so that
    // the IOException catch does not change it.
    final var bytes = new ByteArrayOutputStream();

    try (final var armored = new ArmoredOutputStream(bytes)) {
      final var generator = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);

      try (final var compressed = generator.open(armored)) {
        compressed.write(POM);
      }
    }

    this.assertRefusedWithoutAskingAKeyServer(new ByteArrayResource(bytes.toByteArray()));
  }

  @Test
  @DisplayName("accepts an armored signature without its CRC line, as BouncyCastle does by default")
  void acceptsASignatureWithoutACrcLine() {
    final var lines = armorLines(keys.detachedSignature(POM));
    lines.remove(crcLineIndex(lines));
    final var signature = resource(String.join("\n", lines) + "\n");

    assertThatCode(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("answers itemNotFound when no key server knows the key")
  void answersItemNotFoundWhenNoServerHasTheKey() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(new ByteArrayResource(POM), signature, hosts("keys.acme.com")))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactSigningKeyNotFound");

    // The repo's own server, then both public ones.
    assertThat(this.asked).hasSize(3);
  }

  @Test
  @DisplayName(
      "asks the custom hosts of the repo first, and stops at the first one that has the key")
  void asksTheRepoCustomHostsFirst() {
    final var signature = resource(keys.detachedSignature(POM));
    final var keyId = "%016X".formatted(keys.keyId());
    final var acme = "https://keys.acme.com/pks/lookup?op=get&search=0x" + keyId;
    final var mirror = "https://mirror.acme.com/pks/lookup?op=get&search=0x" + keyId;

    final var service =
        this.serviceAnswering(uri -> uri.toString().equals(mirror) ? keyResponse() : notFound());

    assertThatCode(
            () ->
                service.verify(
                    new ByteArrayResource(POM),
                    signature,
                    // A scheme or a path in the stored host is dropped, only the host is used.
                    hosts("keys.acme.com", "https://mirror.acme.com/somewhere")))
        .doesNotThrowAnyException();

    assertThat(this.asked).extracting(URI::toString).containsExactly(acme, mirror);
  }

  @Test
  @DisplayName("falls back to the public key servers when the custom hosts do not have the key")
  void fallsBackToThePublicServers() {
    final var signature = resource(keys.detachedSignature(POM));

    final var service =
        this.serviceAnswering(
            uri -> uri.getHost().equals("keys.acme.com") ? notFound() : keyResponse());

    assertThatCode(
            () -> service.verify(new ByteArrayResource(POM), signature, hosts("keys.acme.com")))
        .doesNotThrowAnyException();

    assertThat(this.asked.getFirst().getHost()).isEqualTo("keys.acme.com");
    assertThat(this.asked).hasSize(2);
    assertThat(this.asked.get(1).getHost()).isIn("keyserver.ubuntu.com", "keys.openpgp.org");
  }

  @Test
  @DisplayName("ignores a key server answer that is not a key block")
  void ignoresAnAnswerThatIsNotAKeyBlock() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> StubKeyServers.ok("<html>not a key</html>"))
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(ItemNotFoundException.class);
  }

  /** A key block armor whose header matches but whose data is truncated (RPS-1194). */
  private static String corruptedArmoredPublicKey() {
    final var lines = armorLines(keys.armoredPublicKey());
    final var crc = crcLineIndex(lines);
    lines.subList(crc - 3, crc).clear();
    return String.join("\n", lines) + "\n";
  }

  private static ClientHttpResponse keyBlockResponse(final String armoredKeyBlock) {
    return StubKeyServers.ok(armoredKeyBlock);
  }

  @Test
  @DisplayName(
      "a key server answering a corrupt key block is skipped, the next server's key still"
          + " verifies (RPS-1194)")
  void aCorruptKeyBlockFromOneServerIsSkippedForTheNext() {
    final var signature = resource(keys.detachedSignature(POM));
    final var corrupt = corruptedArmoredPublicKey();

    final var service =
        this.serviceAnswering(
            uri ->
                uri.getHost().equals("keyserver.ubuntu.com")
                    ? keyBlockResponse(corrupt)
                    : keyResponse());

    assertThatCode(() -> service.verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .doesNotThrowAnyException();

    // Both key servers are asked: the first one's corrupt answer does not abort the lookup.
    assertThat(this.asked).hasSize(2);
  }

  @Test
  @DisplayName(
      "answers itemNotFound, not a 500 or 422, when every key server answers a corrupt key block"
          + " (RPS-1194)")
  void answersItemNotFoundWhenEveryServerAnswersACorruptKeyBlock() {
    final var signature = resource(keys.detachedSignature(POM));
    final var corrupt = corruptedArmoredPublicKey();

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> keyBlockResponse(corrupt))
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(ItemNotFoundException.class);

    assertThat(this.asked).hasSize(2);
  }

  @Test
  @DisplayName("a registered key verifies a real signature and asks no key server (RPS-1189)")
  void aRegisteredKeyVerifiesWithoutAskingAKeyServer() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .doesNotThrowAnyException();

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("a signature by a different key falls through to the key servers and verifies there")
  void aSignatureByADifferentKeyFallsThroughToKeyServers() {
    final var otherKeys = PgpTestKeys.generate();
    final var signature = resource(otherKeys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> keyResponse(otherKeys))
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .doesNotThrowAnyException();

    // The registered key does not match, so the lookup falls through to the (faked) default key
    // servers; the first one answers with the right key, so it stops there.
    assertThat(this.asked).hasSize(1);
  }

  private static ClientHttpResponse keyResponse(final PgpTestKeys of) {
    return StubKeyServers.ok(of.armoredPublicKey());
  }

  @Test
  @DisplayName("a registered key with the wrong bytes signed is refused, no server is asked")
  void aRegisteredKeyWithWrongBytesSignedIsRefused() {
    final var signature = resource(keys.detachedSignature(OTHER_POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("two registered keys: the second one matches")
  void twoRegisteredKeysTheSecondMatches() {
    final var otherKeys = PgpTestKeys.generate();
    final var signature = resource(otherKeys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey(), otherKeys.armoredPublicKey())))
        .doesNotThrowAnyException();

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("one malformed registered key string is skipped, a second good one still verifies")
  void oneMalformedRegisteredKeyIsSkipped() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys("not a key at all", keys.armoredPublicKey())))
        .doesNotThrowAnyException();

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("refuses a signature made after its (registered) key's validity period (RPS-1202)")
  void refusesASignatureAfterTheRegisteredKeysExpiry() {
    final var expiring = PgpTestKeys.generate().withKeyExpirySeconds(1);
    final var afterExpiry = new Date(System.currentTimeMillis() + 60_000);
    final var signature = resource(expiring.detachedSignature(POM, afterExpiry));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(expiring.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("accepts a signature made within the (registered) key's validity period (RPS-1202)")
  void acceptsASignatureWithinTheRegisteredKeysExpiry() {
    final var expiring = PgpTestKeys.generate().withKeyExpirySeconds(3_600);
    final var signature = resource(expiring.detachedSignature(POM, new Date()));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(expiring.armoredPublicKey())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "refuses a signature verified by a key-server key expired at signing time (RPS-1202)")
  void refusesASignatureAfterAKeyServerKeysExpiry() {
    final var expiring = PgpTestKeys.generate().withKeyExpirySeconds(1);
    final var afterExpiry = new Date(System.currentTimeMillis() + 60_000);
    final var signature = resource(expiring.detachedSignature(POM, afterExpiry));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> keyBlockResponse(expiring.armoredPublicKey()))
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("refuses a signature made with a revoked (registered) key (RPS-1202)")
  void refusesASignatureMadeWithARevokedRegisteredKey() {
    final var revoked = PgpTestKeys.generate().withRevocation();
    final var signature = resource(revoked.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(revoked.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("refuses a signature verified by a revoked key-server key (RPS-1202)")
  void refusesASignatureMadeWithARevokedKeyServerKey() {
    final var revoked = PgpTestKeys.generate().withRevocation();
    final var signature = resource(revoked.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> keyBlockResponse(revoked.armoredPublicKey()))
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName(
      "refuses a signature made with a revoked subkey, even though the primary key is fine"
          + " (RPS-1202)")
  void refusesASignatureMadeWithARevokedSubkey() {
    final var revokedSubkey = PgpTestKeys.generate().withRevokedSubkey();
    final var signature = resource(revokedSubkey.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(revokedSubkey.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("parseArmoredPublicKey reads the primary key's id, a 40 hex char fingerprint")
  void parseArmoredPublicKeyReadsTheIdentity() {
    final var parsed = PgpVerifierService.parseArmoredPublicKey(keys.armoredPublicKey());

    assertThat(parsed.keyIdHex()).isEqualTo("%016X".formatted(keys.keyId()));
    assertThat(parsed.fingerprintHex()).hasSize(40).matches("[0-9A-F]{40}");
  }

  @Test
  @DisplayName("parseArmoredPublicKey refuses plain text")
  void parseArmoredPublicKeyRefusesPlainText() {
    assertThatThrownBy(() -> PgpVerifierService.parseArmoredPublicKey("hello"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(INVALID_KEY);
  }

  @Test
  @DisplayName("parseArmoredPublicKey refuses a signature block")
  void parseArmoredPublicKeyRefusesASignatureBlock() {
    assertThatThrownBy(() -> PgpVerifierService.parseArmoredPublicKey(keys.detachedSignature(POM)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(INVALID_KEY);
  }

  @Test
  @DisplayName("parseArmoredPublicKey refuses a private-key block")
  void parseArmoredPublicKeyRefusesAPrivateKeyBlock() {
    final var privateKeyArmor =
        "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\n" + keys.armoredPublicKey();

    assertThatThrownBy(() -> PgpVerifierService.parseArmoredPublicKey(privateKeyArmor))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(INVALID_KEY);
  }

  @Test
  @DisplayName("parseArmoredPublicKey refuses a block holding two rings")
  void parseArmoredPublicKeyRefusesTwoRings() throws Exception {
    final var otherKeys = PgpTestKeys.generate();
    final var twoRings = twoKeyRingArmor(keys, otherKeys);

    assertThatThrownBy(() -> PgpVerifierService.parseArmoredPublicKey(twoRings))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(INVALID_KEY);
  }

  /**
   * A single armored block holding both keys' rings, the way {@code gpg --export --armor} would
   * export a keyring of two keys (one ASCII-armored block wrapping every key's binary packets, not
   * two armored blocks concatenated: {@link org.bouncycastle.openpgp.PGPPublicKeyRingCollection}
   * only reads the first armor block of a stream, so simply concatenating two exports would only
   * ever be read as the first key).
   */
  private static String twoKeyRingArmor(final PgpTestKeys a, final PgpTestKeys b)
      throws IOException, PGPException {

    final var collection =
        new PGPPublicKeyRingCollection(
            List.of(singleRingOf(a.armoredPublicKey()), singleRingOf(b.armoredPublicKey())));

    final var bytes = new ByteArrayOutputStream();

    try (final var armored = new ArmoredOutputStream(bytes)) {
      collection.encode(armored);
    }

    return bytes.toString(UTF_8);
  }

  private static PGPPublicKeyRing singleRingOf(final String armored)
      throws IOException, PGPException {

    try (final var ds =
        PGPUtil.getDecoderStream(new ByteArrayInputStream(armored.getBytes(UTF_8)))) {
      final var collection = new PGPPublicKeyRingCollection(ds, new JcaKeyFingerprintCalculator());

      return collection.getKeyRings().next();
    }
  }

  @Test
  @DisplayName("a registered key is parsed once however many signatures are verified (RPS-1814)")
  void aRegisteredKeyIsParsedOnce() {
    final var service = this.serviceAnswering(uri -> notFound());
    final var sources = registeredKeys(keys.armoredPublicKey());
    final var signature = resource(keys.detachedSignature(POM));

    service.verify(new ByteArrayResource(POM), signature, sources);
    service.verify(new ByteArrayResource(POM), signature, sources);
    service.verify(new ByteArrayResource(POM), signature, sources);

    assertThat(service.parsedRegisteredKeyStats().loadSuccessCount()).isEqualTo(1);
    assertThat(service.parsedRegisteredKeyStats().hitCount()).isEqualTo(2);
    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("evicting a deleted key makes the next lookup parse it again (RPS-1814)")
  void evictingAKeyForgetsItsParsedForm() {
    final var service = this.serviceAnswering(uri -> notFound());
    final var armored = keys.armoredPublicKey();
    final var signature = resource(keys.detachedSignature(POM));

    service.verify(new ByteArrayResource(POM), signature, registeredKeys(armored));
    assertThat(service.parsedRegisteredKeyCount()).isEqualTo(1);

    service.evictRegisteredKey(armored);
    assertThat(service.parsedRegisteredKeyCount()).isZero();

    service.verify(new ByteArrayResource(POM), signature, registeredKeys(armored));
    assertThat(service.parsedRegisteredKeyStats().loadSuccessCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a changed armored key is never answered by the ring parsed from the old text")
  void aChangedArmoredKeyIsParsedAnew() {
    final var service = this.serviceAnswering(uri -> notFound());
    final var other = PgpTestKeys.generate();
    final var signature = resource(other.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                service.verify(
                    new ByteArrayResource(POM), signature, registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(ItemNotFoundException.class);

    assertThatCode(
            () ->
                service.verify(
                    new ByteArrayResource(POM),
                    signature,
                    registeredKeys(other.armoredPublicKey())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a registered key that does not parse is not cached and does not break the lookup")
  void aCorruptKeyIsNotCached() {
    final var service = this.serviceAnswering(uri -> notFound());
    final var signature = resource(keys.detachedSignature(POM));

    service.verify(
        new ByteArrayResource(POM),
        signature,
        registeredKeys(
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nnot a key", keys.armoredPublicKey()));

    assertThat(service.parsedRegisteredKeyCount()).isEqualTo(1);
  }

  // RPS-2067: the rest of the verification outcome matrix, pinned on the backend class before it
  // moves into repsy-protocols/maven. Where an outcome is today's behaviour rather than a policy
  // (no weak-digest policy, an inline-signed message accepted), the test says so.

  @Test
  @DisplayName("accepts a signature made by a bound subkey, looked up by the subkey's id")
  void acceptsASignatureMadeWithASubkey() {
    final var subkey = PgpTestKeys.generate().withSigningSubkey();
    final var signature = resource(subkey.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(subkey.armoredPublicKey())))
        .doesNotThrowAnyException();

    assertThat(this.serviceAnswering(uri -> notFound()).readSignerKeyId(signature))
        .isEqualTo("%016X".formatted(subkey.keyId()));
  }

  @Test
  @DisplayName("accepts a subkey signature whose ring came from a key server")
  void acceptsASubkeySignatureFromAKeyServerRing() {
    final var subkey = PgpTestKeys.generate().withSigningSubkey();
    final var signature = resource(subkey.detachedSignature(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> keyBlockResponse(subkey.armoredPublicKey()))
                    .verify(new ByteArrayResource(POM), signature, noRegisteredKeys()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a signature made by a fine subkey whose primary key is revoked")
  void refusesASubkeySignatureWhenThePrimaryKeyIsRevoked() {
    final var subkey = PgpTestKeys.generate().withRevocation().withSigningSubkey();
    final var signature = resource(subkey.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(subkey.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("answers artifactSigningKeyNotFound for a signature by a key nobody has")
  void answersKeyNotFoundForASignatureByAnUnknownKey() {
    final var stranger = PgpTestKeys.generate();
    final var signature = resource(stranger.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> keyResponse())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactSigningKeyNotFound");

    // Every key server answered a block, but with another key: the two defaults were both asked.
    assertThat(this.asked).hasSize(2);
  }

  @Test
  @DisplayName("refuses a signature dated before its key was created when the key has an expiry")
  void refusesASignatureDatedBeforeAnExpiringKeysCreation() {
    final var expiring = PgpTestKeys.generate().withKeyExpirySeconds(3_600);
    final var beforeCreation = new Date(System.currentTimeMillis() - 3_600_000);
    final var signature = resource(expiring.detachedSignature(POM, beforeCreation));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(expiring.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("accepts a signature dated before its key's creation when the key never expires")
  void acceptsABackdatedSignatureOfAKeyWithoutExpiry() {
    // Today's behaviour: the creation-time window is only checked for a key with an expiry.
    final var beforeCreation = new Date(System.currentTimeMillis() - 3_600_000);
    final var signature = resource(keys.detachedSignature(POM, beforeCreation));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(ints = {HashAlgorithmTags.SHA1, HashAlgorithmTags.MD5, HashAlgorithmTags.SHA512})
  @DisplayName("has no digest policy: a SHA-1, MD5 or SHA-512 signature verifies like SHA-256")
  void hasNoWeakDigestPolicy(final int hashAlgorithm) {
    // Today's behaviour, not a decision: BouncyCastle verifies any digest it implements, and the
    // service does not refuse weak ones.
    final var signature = resource(keys.detachedSignature(POM, hashAlgorithm));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a weak-digest signature over other bytes is still refused")
  void refusesAWeakDigestSignatureOfOtherBytes() {
    final var signature = resource(keys.detachedSignature(OTHER_POM, HashAlgorithmTags.SHA1));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName(
      "accepts an inline-signed message (gpg --sign) whose literal data is the stored file")
  void acceptsAnInlineSignedMessageOfTheSameBytes() {
    // Today's behaviour, not a decision: the signature packet after the literal data is taken and
    // checked against the stored file, so it verifies like the detached signature of those bytes.
    final var signature = resource(keys.inlineSignedMessage(POM));

    assertThatCode(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses an inline-signed message of other bytes")
  void refusesAnInlineSignedMessageOfOtherBytes() {
    final var signature = resource(keys.inlineSignedMessage(OTHER_POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("refuses a cleartext-signed message (gpg --clearsign), even of the stored text")
  void refusesAClearSignedMessageOfTheStoredText() {
    final var text = new String(POM, UTF_8);
    final var signature = resource(keys.clearSignedMessage(text));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(
                        new ByteArrayResource(POM),
                        signature,
                        registeredKeys(keys.armoredPublicKey())))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("with no key sources at all (null) the default key servers are asked")
  void nullSourcesAskTheDefaultKeyServers() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () -> this.serviceWithTheKey().verify(new ByteArrayResource(POM), signature, null))
        .doesNotThrowAnyException();

    assertThat(this.asked).hasSize(1);
  }
}
