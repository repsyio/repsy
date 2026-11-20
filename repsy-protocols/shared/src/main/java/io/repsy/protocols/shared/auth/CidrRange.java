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
package io.repsy.protocols.shared.auth;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.List;

/**
 * An address range, {@code 10.0.0.0/8}; a bare address is a range of one. The allowlists of the
 * probe ban ({@link SuspiciousRequestThrottle}) and of the auth throttle ({@link
 * AuthFailureThrottle}, RPS-2664) are lists of these.
 */
final class CidrRange {

  private static final int BITS_PER_BYTE = 8;

  private final byte[] network;
  private final int prefixBits;

  private CidrRange(final byte[] network, final int prefixBits) {

    this.network = network.clone();
    this.prefixBits = prefixBits;
  }

  /**
   * Parses every entry; a bad one fails with a message that names {@code setting}, so a typo stops
   * the server at startup instead of silently allowing nothing.
   */
  static List<CidrRange> parseAll(final List<String> entries, final String setting) {

    return entries.stream()
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .map(entry -> parse(entry, setting))
        .toList();
  }

  static CidrRange parse(final String text, final String setting) {

    final var slash = text.indexOf('/');
    final var host = slash < 0 ? text : text.substring(0, slash);
    final byte[] bytes;

    try {
      bytes = InetAddresses.forString(host.trim()).getAddress();
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(setting + ": not an address or CIDR range: " + text, e);
    }

    final int bits;

    try {
      bits =
          slash < 0
              ? bytes.length * BITS_PER_BYTE
              : Integer.parseInt(text.substring(slash + 1).trim());
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(setting + ": bad prefix length: " + text, e);
    }

    if (bits < 0 || bits > bytes.length * BITS_PER_BYTE) {
      throw new IllegalArgumentException(setting + ": bad prefix length: " + text);
    }

    return new CidrRange(bytes, bits);
  }

  /** Whether {@code remoteAddr} is an address in one of {@code ranges}; not an address is never. */
  static boolean anyContains(final List<CidrRange> ranges, final String remoteAddr) {

    if (ranges.isEmpty()) {
      return false;
    }

    final InetAddress address;

    try {
      address = InetAddresses.forString(remoteAddr);
    } catch (final IllegalArgumentException _) {
      return false;
    }

    return ranges.stream().anyMatch(range -> range.contains(address));
  }

  boolean contains(final InetAddress address) {

    final var other = address.getAddress();

    if (other.length != this.network.length) {
      return false;
    }

    for (var bit = 0; bit < this.prefixBits; bit += BITS_PER_BYTE) {
      final var index = bit / BITS_PER_BYTE;
      final var remaining = this.prefixBits - bit;
      final var mask =
          remaining >= BITS_PER_BYTE ? 0xFF : (0xFF << (BITS_PER_BYTE - remaining)) & 0xFF;

      if ((this.network[index] & mask) != (other[index] & mask)) {
        return false;
      }
    }

    return true;
  }
}
