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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CidrRange (RPS-2664)")
class CidrRangeTest {

  private static final String SETTING = "repsy.security.auth-throttle.allowlist";

  @Test
  @DisplayName("a range holds the addresses of its prefix, a bare address only itself")
  void containsByPrefix() {
    final var ranges =
        CidrRange.parseAll(List.of("10.42.0.0/16", "2001:db8::/32", "192.0.2.5"), SETTING);

    assertThat(CidrRange.anyContains(ranges, "10.42.255.1")).isTrue();
    assertThat(CidrRange.anyContains(ranges, "10.43.0.1")).isFalse();
    assertThat(CidrRange.anyContains(ranges, "2001:db8:1::5")).isTrue();
    assertThat(CidrRange.anyContains(ranges, "192.0.2.5")).isTrue();
    assertThat(CidrRange.anyContains(ranges, "192.0.2.6")).isFalse();
  }

  @Test
  @DisplayName("a prefix that is not a whole byte is matched bit by bit")
  void partialBytePrefix() {
    final var ranges = CidrRange.parseAll(List.of("10.0.0.0/12"), SETTING);

    assertThat(CidrRange.anyContains(ranges, "10.15.255.255")).isTrue();
    assertThat(CidrRange.anyContains(ranges, "10.16.0.0")).isFalse();
  }

  @Test
  @DisplayName("an empty list, a host name and the other address family hold nothing")
  void holdsNothingElse() {
    final var ipv4 = CidrRange.parseAll(List.of("0.0.0.0/0"), SETTING);

    assertThat(CidrRange.anyContains(List.of(), "10.0.0.1")).isFalse();
    assertThat(CidrRange.anyContains(ipv4, "gateway.internal")).isFalse();
    assertThat(CidrRange.anyContains(ipv4, "::1")).isFalse();
  }

  @Test
  @DisplayName("blank entries are skipped and spaces trimmed")
  void skipsBlankEntries() {
    assertThat(CidrRange.parseAll(List.of(" ", "", " 10.0.0.1 "), SETTING)).hasSize(1);
  }

  @Test
  @DisplayName("a bad entry names the setting")
  void refusesBadEntries() {
    for (final var bad : List.of("nope", "10.0.0.0/-1", "10.0.0.0/33", "10.0.0.0/x", "::/129")) {
      final var entries = List.of(bad);

      assertThatThrownBy(() -> CidrRange.parseAll(entries, SETTING))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith(SETTING);
    }
  }
}
