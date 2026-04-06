package io.repsy.protocols.cargo.protocol.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CargoDigestCalculator {

  private static final String SHA256 = "SHA-256";

  public String computeDigest(final byte[] bytes) {

    try {
      final var digest = MessageDigest.getInstance(SHA256);
      final var hash = digest.digest(bytes);
      return HexFormat.of().formatHex(hash);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(SHA256 + " algorithm not available", e);
    }
  }
}
