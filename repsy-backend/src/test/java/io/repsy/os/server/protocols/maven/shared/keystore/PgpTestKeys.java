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
import java.util.Date;
import java.util.List;
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
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;

/**
 * A throw-away RSA key pair for tests that need a real OpenPGP signature without a key server or a
 * {@code gpg} binary: the armored public key a key server would answer with, and detached ASCII
 * armored signatures made with the private key, the way {@code gpg --armor --detach-sign} does.
 */
public final class PgpTestKeys {

  private static final int RSA_BITS = 2_048;

  private final PGPKeyPair keyPair;

  private PgpTestKeys(final PGPKeyPair keyPair) {
    this.keyPair = keyPair;
  }

  /** Generates a new key pair. Two calls give two unrelated keys with different key ids. */
  public static PgpTestKeys generate() {

    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }

    try {
      final var generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
      generator.initialize(RSA_BITS);

      return new PgpTestKeys(
          new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), new Date()));
    } catch (final GeneralSecurityException | PGPException e) {
      throw new IllegalStateException("cannot generate a test key pair", e);
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

      try (final var armored = new ArmoredOutputStream(bytes)) {
        new PGPPublicKeyRing(List.of(this.keyPair.getPublicKey())).encode(armored);
      }

      return bytes.toString(StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** An ASCII armored detached signature of exactly these bytes. */
  public String detachedSignature(final byte[] data) {

    try {
      final var generator =
          new PGPSignatureGenerator(
              new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME),
              this.keyPair.getPublicKey());
      generator.init(PGPSignature.BINARY_DOCUMENT, this.keyPair.getPrivateKey());
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
