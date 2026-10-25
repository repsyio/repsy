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

import com.google.common.base.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SuspiciousRequestThrottle")
class SuspiciousRequestThrottleTest {

  private static final String CLIENT = "203.0.113.7";
  private static final Set<SignalFamily> TRAVERSAL = Set.of(SignalFamily.TRAVERSAL);

  private final AtomicLong nanos = new AtomicLong();
  private final Ticker ticker =
      new Ticker() {
        @Override
        public long read() {
          return SuspiciousRequestThrottleTest.this.nanos.get();
        }
      };
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private SuspiciousRequestThrottle throttle(final SuspiciousRequestProperties properties) {

    return new SuspiciousRequestThrottle(properties, this.registry, this.ticker);
  }

  private void advanceSeconds(final long seconds) {

    this.nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
  }

  @Test
  @DisplayName("strikes add up in a window and ban the client at the limit")
  void strikesBanAtTheLimit() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));

    assertThat(throttle.record(CLIENT, CLIENT, TRAVERSAL)).isZero();
    assertThat(throttle.banSecondsLeft(CLIENT)).isZero();
    assertThat(throttle.record(CLIENT, CLIENT, TRAVERSAL)).isEqualTo(3600);
    assertThat(throttle.banSecondsLeft(CLIENT)).isEqualTo(3600);
    assertThat(this.registry.counter("repsy.security.probe_bans").count()).isEqualTo(1);
    assertThat(this.registry.counter("repsy.security.probe_strikes", "family", "traversal").count())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("one request that weighs the limit bans at once")
  void heavyRequestBansAtOnce() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));

    final var left =
        throttle.record(CLIENT, CLIENT, Set.of(SignalFamily.TRAVERSAL, SignalFamily.SECRET_PATH));

    assertThat(left).isEqualTo(3600);
  }

  @Test
  @DisplayName("the strikes of a window are forgotten when it ends")
  void windowExpires() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    this.advanceSeconds(61);
    throttle.record(CLIENT, CLIENT, TRAVERSAL);

    assertThat(throttle.banSecondsLeft(CLIENT)).isZero();
  }

  @Test
  @DisplayName("a ban ends after ban-seconds")
  void banExpires() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 5, 100));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    this.advanceSeconds(40);
    assertThat(throttle.banSecondsLeft(CLIENT)).isEqualTo(60);
    this.advanceSeconds(61);

    assertThat(throttle.banSecondsLeft(CLIENT)).isZero();
  }

  @Test
  @DisplayName("clients are counted apart")
  void clientsAreSeparate() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 5, 3600));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);

    assertThat(throttle.banSecondsLeft("203.0.113.8")).isZero();
  }

  @Test
  @DisplayName("in observe mode the client is banned in the books too")
  void observeTracksTheSame() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.OBSERVE, 5, 3600));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    throttle.countWouldBlock(CLIENT, 3600);

    assertThat(throttle.banSecondsLeft(CLIENT)).isEqualTo(3600);
    assertThat(this.registry.counter("repsy.security.probe_would_block").count()).isEqualTo(1);
  }

  @Test
  @DisplayName("in mode off nothing is tracked")
  void offTracksNothing() {

    final var throttle = this.throttle(SuspiciousRequestProperties.disabled());

    assertThat(throttle.isActive()).isFalse();
    assertThat(throttle.record(CLIENT, CLIENT, TRAVERSAL)).isZero();
    assertThat(throttle.banSecondsLeft(CLIENT)).isZero();
  }

  @Test
  @DisplayName("reset forgets bans and strikes")
  void resetForgets() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 5, 3600));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    throttle.reset();

    assertThat(throttle.banSecondsLeft(CLIENT)).isZero();
  }

  @Test
  @DisplayName("the allowlist takes addresses and CIDR ranges, IPv4 and IPv6")
  void allowlistMatches() {

    final var properties =
        new SuspiciousRequestProperties(
            AuthThrottleMode.ENFORCE,
            10,
            60L,
            3600L,
            10_000L,
            List.of("10.0.0.0/8", "192.0.2.5", "2001:db8::/32"),
            null);
    final var throttle = this.throttle(properties);

    assertThat(throttle.isAllowlisted("10.200.3.4")).isTrue();
    assertThat(throttle.isAllowlisted("192.0.2.5")).isTrue();
    assertThat(throttle.isAllowlisted("192.0.2.6")).isFalse();
    assertThat(throttle.isAllowlisted("11.0.0.1")).isFalse();
    assertThat(throttle.isAllowlisted("2001:db8:1::1")).isTrue();
    assertThat(throttle.isAllowlisted("2001:db9::1")).isFalse();
    assertThat(throttle.isAllowlisted("not-an-address")).isFalse();
  }

  @Test
  @DisplayName("a bad allowlist entry fails at startup")
  void badAllowlistEntryFails() {

    final var properties =
        new SuspiciousRequestProperties(
            AuthThrottleMode.ENFORCE, 10, 60L, 3600L, 10_000L, List.of("10.0.0.0/33"), null);

    assertThatThrownBy(() -> this.throttle(properties))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a scanner's User-Agent adds to other strikes but never bans a client alone")
  void scannerAgentNeverBansAlone() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));
    final var agent = Set.of(SignalFamily.SCANNER_UA);

    for (var i = 0; i < 20; i++) {
      assertThat(throttle.record(CLIENT, CLIENT, agent)).isZero();
    }

    // The 60 soft strikes are already there: one more strike of a family that can ban tips it.
    assertThat(throttle.record(CLIENT, CLIENT, Set.of(SignalFamily.DOUBLE_ENCODED)))
        .isEqualTo(3600);
  }

  private record Announced(BanKind kind, String client, long banSeconds) {}

  private static ClientBanListener recordingInto(final List<Announced> into) {

    return (kind, client, banSeconds) -> into.add(new Announced(kind, client, banSeconds));
  }

  @Test
  @DisplayName("a new ban is announced once, with its length")
  void banIsAnnouncedOnce() {

    final var announced = new ArrayList<Announced>();
    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));
    throttle.setBanListener(recordingInto(announced));

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    assertThat(announced).isEmpty();

    throttle.record(CLIENT, CLIENT, TRAVERSAL);
    throttle.record(CLIENT, CLIENT, TRAVERSAL);

    assertThat(announced).containsExactly(new Announced(BanKind.PROBE, CLIENT, 3600));
  }

  @Test
  @DisplayName("an adopted ban refuses for the time left, is clamped, and is not announced")
  void adoptedBanIsKeptButNotAnnounced() {

    final var announced = new ArrayList<Announced>();
    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));
    throttle.setBanListener(recordingInto(announced));

    throttle.adopt(CLIENT, 120);
    throttle.adopt("198.51.100.9", 7200);
    throttle.adopt("198.51.100.10", 0);

    assertThat(throttle.banSecondsLeft(CLIENT)).isEqualTo(120);
    assertThat(throttle.banSecondsLeft("198.51.100.9")).isEqualTo(3600);
    assertThat(throttle.banSecondsLeft("198.51.100.10")).isZero();

    this.advanceSeconds(60);
    throttle.adopt(CLIENT, 3000);

    assertThat(throttle.banSecondsLeft(CLIENT)).isEqualTo(60);
    assertThat(announced).isEmpty();
  }

  @Test
  @DisplayName("a listener that fails does not stop the ban")
  void failingListenerIsIgnored() {

    final var throttle =
        this.throttle(SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));
    throttle.setBanListener(
        (kind, client, banSeconds) -> {
          throw new IllegalStateException("store is down");
        });

    assertThat(
            throttle.record(
                CLIENT, CLIENT, Set.of(SignalFamily.TRAVERSAL, SignalFamily.SECRET_PATH)))
        .isEqualTo(3600);
  }
}
