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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.ArrayList;
import java.util.Date;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;

/**
 * A throw-away RSA key pair for tests that need a real OpenPGP signature without a key server or a
 * {@code gpg} binary: the armored public key a key server would answer with, and detached ASCII
 * armored signatures made with the private key, the way {@code gpg --armor --detach-sign} does.
 *
 * <p>RPS-1202: {@link #withKeyExpirySeconds}, {@link #withRevocation()} and {@link
 * #withRevokedSubkey()} build keys whose {@code armoredPublicKey()} carries the self-signature
 * (expiration, revocation, subkey binding/revocation) BouncyCastle's {@code hasRevocation()} and
 * {@code getValidSeconds()} read.
 */
public final class PgpTestKeys {

  private static final int RSA_BITS = 2_048;

  private final PGPKeyPair keyPair;

  /**
   * Only set for a key built by {@link #withRevokedSubkey()}: {@link #keyPair} is then the subkey
   * (so {@link #keyId()} and {@link #detachedSignature} refer to it, the way a signature made by a
   * subkey does), and this is the ring's primary key, placed first when {@link #armoredPublicKey()}
   * encodes the two-key ring.
   */
  private final PGPPublicKey ringPrimaryKey;

  private PgpTestKeys(final PGPKeyPair keyPair) {
    this(keyPair, null);
  }

  private PgpTestKeys(final PGPKeyPair keyPair, final PGPPublicKey ringPrimaryKey) {
    this.keyPair = keyPair;
    this.ringPrimaryKey = ringPrimaryKey;
  }

  /** Generates a new key pair. Two calls give two unrelated keys with different key ids. */
  public static PgpTestKeys generate() {
    return new PgpTestKeys(newRsaKeyPair(new Date()));
  }

  private static PGPKeyPair newRsaKeyPair(final Date creationTime) {

    ensureBouncyCastleProvider();

    try {
      final var generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
      generator.initialize(RSA_BITS);

      return new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), creationTime);
    } catch (final GeneralSecurityException | PGPException e) {
      throw new IllegalStateException("cannot generate a test key pair", e);
    }
  }

  private static void ensureBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /**
   * This same key, with a direct-key self-signature (RFC 4880 {@code 0x1F}) added whose validity
   * period is {@code validSeconds} after the key's own creation time: {@code getValidSeconds()}
   * then reports it, the way a real {@code gpg --edit-key expire} certification would.
   */
  public PgpTestKeys withKeyExpirySeconds(final long validSeconds) {

    final var subpackets = new PGPSignatureSubpacketGenerator();
    subpackets.setKeyExpirationTime(false, validSeconds);

    final var certified =
        PGPPublicKey.addCertification(
            this.keyPair.getPublicKey(),
            this.selfCertification(PGPSignature.DIRECT_KEY, subpackets));

    return new PgpTestKeys(new PGPKeyPair(certified, this.keyPair.getPrivateKey()));
  }

  /**
   * This same (primary) key, with a {@code KEY_REVOCATION} self-signature added: {@code
   * hasRevocation()} then reports it, the way a real published revocation certificate would.
   */
  public PgpTestKeys withRevocation() {

    final var revoked =
        PGPPublicKey.addCertification(
            this.keyPair.getPublicKey(),
            this.selfCertification(
                PGPSignature.KEY_REVOCATION, new PGPSignatureSubpacketGenerator()));

    return new PgpTestKeys(new PGPKeyPair(revoked, this.keyPair.getPrivateKey()));
  }

  private PGPSignature selfCertification(
      final int signatureType, final PGPSignatureSubpacketGenerator hashedSubpackets) {

    try {
      final var generator =
          new PGPSignatureGenerator(
              new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME),
              this.keyPair.getPublicKey());
      generator.init(signatureType, this.keyPair.getPrivateKey());
      generator.setHashedSubpackets(hashedSubpackets.generate());

      return generator.generateCertification(this.keyPair.getPublicKey());
    } catch (final PGPException e) {
      throw new IllegalStateException("cannot generate a self-certification", e);
    }
  }

  /**
   * A fresh two-key ring: this key as the primary, plus a subkey that carries both a binding
   * signature and a {@code SUBKEY_REVOCATION} signature, both issued by the primary's private key,
   * the way {@code gpg --edit-key revkey} would produce. The returned {@code PgpTestKeys} signs
   * with the (revoked) subkey, so {@link #keyId()} and {@link #detachedSignature} refer to it, and
   * {@link #armoredPublicKey()} encodes the whole ring, primary key first.
   */
  public PgpTestKeys withRevokedSubkey() {

    try {
      final var primary = this.keyPair;
      // A freshly generated PGPKeyPair is a master-type key packet; asSubkey() is what actually
      // turns it into a subkey packet, which is what makes PGPPublicKey.isMasterKey() false and
      // lets addCertification(...) accept a SUBKEY_REVOCATION signature on it below.
      final var sub = newRsaKeyPair(new Date()).asSubkey(new JcaKeyFingerprintCalculator());

      final var bindingGenerator =
          new PGPSignatureGenerator(
              new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME),
              primary.getPublicKey());
      bindingGenerator.init(PGPSignature.SUBKEY_BINDING, primary.getPrivateKey());
      final var binding =
          bindingGenerator.generateCertification(primary.getPublicKey(), sub.getPublicKey());
      final var boundSub = PGPPublicKey.addCertification(sub.getPublicKey(), binding);

      final var revocationGenerator =
          new PGPSignatureGenerator(
              new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME),
              primary.getPublicKey());
      revocationGenerator.init(PGPSignature.SUBKEY_REVOCATION, primary.getPrivateKey());
      final var revocation =
          revocationGenerator.generateCertification(primary.getPublicKey(), boundSub);
      final var revokedSub = PGPPublicKey.addCertification(boundSub, revocation);

      return new PgpTestKeys(
          new PGPKeyPair(revokedSub, sub.getPrivateKey()), primary.getPublicKey());
    } catch (final PGPException e) {
      throw new IllegalStateException("cannot generate a revoked test subkey", e);
    }
  }

  /** The 64 bit key id a signature made with this key carries. */
  public long keyId() {
    return this.keyPair.getKeyID();
  }

  /** The public key as a key server answers {@code op=get}: one ASCII armored key block. */
  public String armoredPublicKey() {

    try {
      final var bytes = new ByteArrayOutputStream();
      final var ring = new ArrayList<PGPPublicKey>();

      if (this.ringPrimaryKey != null) {
        ring.add(this.ringPrimaryKey);
      }
      ring.add(this.keyPair.getPublicKey());

      try (final var armored = new ArmoredOutputStream(bytes)) {
        new PGPPublicKeyRing(ring).encode(armored);
      }

      return bytes.toString(StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** An ASCII armored detached signature of exactly these bytes, dated {@code new Date()}. */
  public String detachedSignature(final byte[] data) {
    return this.detachedSignature(data, new Date());
  }

  /**
   * An ASCII armored detached signature of exactly these bytes, with its {@code
   * signature-creation-time} hashed subpacket forced to {@code creationTime} (RPS-1202: lets a test
   * date a signature after a key's expiry window without actually waiting for it).
   */
  public String detachedSignature(final byte[] data, final Date creationTime) {

    try {
      final var generator =
          new PGPSignatureGenerator(
              new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME),
              this.keyPair.getPublicKey());
      generator.init(PGPSignature.BINARY_DOCUMENT, this.keyPair.getPrivateKey());

      final var subpackets = new PGPSignatureSubpacketGenerator();
      subpackets.setSignatureCreationTime(false, creationTime);
      generator.setHashedSubpackets(subpackets.generate());

      generator.update(data);

      final var bytes = new ByteArrayOutputStream();

      try (final var armored = new ArmoredOutputStream(bytes)) {
        generator.generate().encode(new BCPGOutputStream(armored));
      }

      return bytes.toString(StandardCharsets.UTF_8);
    } catch (final PGPException e) {
      throw new IllegalStateException("cannot sign the test data", e);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
