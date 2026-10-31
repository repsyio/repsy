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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.base.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RPS-1092: failed password checks are counted per client, in a fixed window, and only failed ones
 * count. The clock is fake, so no test waits for a window.
 */
@DisplayName("AuthFailureThrottle")
class AuthFailureThrottleTest {

  private static final int MAX_FAILURES = 3;
  private static final long WINDOW_SECONDS = 60;
  private static final String CLIENT = "203.0.113.7";
  private static final String OTHER_CLIENT = "203.0.113.8";
  private static final String IPV6_CLIENT = "2001:db8:1234:5678::1";
  private static final String IPV6_CLIENT_SAME_NETWORK = "2001:db8:1234:5678:ffff::2";
  private static final String IPV6_CLIENT_OTHER_NETWORK = "2001:db8:1234:9999::1";

  private final AtomicLong nanos = new AtomicLong();

  private final Ticker ticker =
      new Ticker() {
        @Override
        public long read() {
          return AuthFailureThrottleTest.this.nanos.get();
        }
      };

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final AuthFailureThrottle throttle =
      new AuthFailureThrottle(
          AuthThrottleProperties.enforcing(MAX_FAILURES, WINDOW_SECONDS, 100),
          this.meterRegistry,
          this.ticker);

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(AuthFailureThrottle.class);

  @BeforeEach
  void captureLogs() {
    this.logs.start();
    this.logger.addAppender(this.logs);
  }

  @AfterEach
  void clearRequest() {
    this.logger.detachAppender(this.logs);
    RequestContextHolder.resetRequestAttributes();
  }

  private static void requestFrom(final String remoteAddr) {
    final var request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void advance(final Duration duration) {
    this.nanos.addAndGet(duration.toNanos());
  }

  private void fail(final int times) {
    for (var i = 0; i < times; i++) {
      this.throttle.recordFailure();
    }
  }

  @Test
  @DisplayName("lets a client fail up to the limit and refuses the next password check")
  void blocksAfterTheLimit() {
    requestFrom(CLIENT);

    this.fail(MAX_FAILURES - 1);
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();

    this.fail(1);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  @DisplayName("counts clients apart")
  void countsClientsApart() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES);

    requestFrom(OTHER_CLIENT);
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();

    requestFrom(CLIENT);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  @DisplayName("tells a blocked client how long is left of its window, rounded up")
  void retryAfter() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES);

    assertThat(this.throttle.retryAfterSeconds()).isEqualTo(WINDOW_SECONDS);

    this.advance(Duration.ofMillis(10_500));
    assertThat(this.throttle.retryAfterSeconds()).isEqualTo(50);

    this.advance(Duration.ofSeconds(49).plusMillis(400));
    assertThat(this.throttle.retryAfterSeconds()).isEqualTo(1);

    assertThatThrownBy(this.throttle::checkAllowed)
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
  }

  @Test
  @DisplayName("starts a client over exactly when its window ends")
  void windowBoundary() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES);

    this.advance(Duration.ofSeconds(WINDOW_SECONDS).minusNanos(1));
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);

    this.advance(Duration.ofNanos(1));
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();

    // A new window, with a clean count.
    this.fail(MAX_FAILURES - 1);
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
    this.fail(1);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  @DisplayName("does not extend the window with each failure")
  void windowIsFixed() {
    requestFrom(CLIENT);
    this.fail(1);

    this.advance(Duration.ofSeconds(WINDOW_SECONDS - 1));
    this.fail(MAX_FAILURES - 1);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);

    this.advance(Duration.ofSeconds(1));
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("counts the refused attempts too, so a blocked guesser saturates the throttle")
  void refusedAttemptsSaturate() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES);
    assertThat(this.throttle.isSaturated()).isFalse();

    final var saturationLimit = MAX_FAILURES * AuthFailureThrottle.SATURATION_FACTOR;

    for (var refused = MAX_FAILURES; refused < saturationLimit - 1; refused++) {
      assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
      assertThat(this.throttle.isSaturated()).isFalse();
    }

    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
    assertThat(this.throttle.isSaturated()).isTrue();

    // Counting stops at the limit and the window still ends on time.
    this.fail(1_000);
    assertThat(this.throttle.isSaturated()).isTrue();

    this.advance(Duration.ofSeconds(WINDOW_SECONDS));
    assertThat(this.throttle.isSaturated()).isFalse();
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("throttles two IPv6 addresses of the same /64 network together")
  void ipv6SameNetworkSharesTheCount() {
    requestFrom(IPV6_CLIENT);
    this.fail(MAX_FAILURES);

    requestFrom(IPV6_CLIENT_SAME_NETWORK);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  @DisplayName("does not share the count between two different IPv6 /64 networks")
  void ipv6DifferentNetworksAreCountedApart() {
    requestFrom(IPV6_CLIENT);
    this.fail(MAX_FAILURES);

    requestFrom(IPV6_CLIENT_OTHER_NETWORK);
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("logs the full IPv6 address of a blocked client, not only its throttled network")
  void ipv6LogsTheFullAddress() {
    requestFrom(IPV6_CLIENT);
    this.fail(MAX_FAILURES);

    assertThat(this.logs.list).hasSize(1);
    assertThat(this.logs.list.getFirst().getFormattedMessage()).contains(IPV6_CLIENT);
  }

  @Test
  @DisplayName("logs the address once per window when a client becomes blocked")
  void logsOncePerWindow() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES - 1);
    assertThat(this.logs.list).isEmpty();

    this.fail(1);
    assertThat(this.logs.list).hasSize(1);
    final var event = this.logs.list.getFirst();
    assertThat(event.getLevel()).hasToString("INFO");
    assertThat(event.getFormattedMessage()).contains(CLIENT);

    this.fail(5);
    assertThatThrownBy(this.throttle::checkAllowed).isInstanceOf(TooManyRequestsException.class);
    assertThat(this.logs.list).hasSize(1);

    this.advance(Duration.ofSeconds(WINDOW_SECONDS));
    this.fail(MAX_FAILURES);
    assertThat(this.logs.list).hasSize(2);
  }

  @Test
  @DisplayName("tracks a bounded number of clients")
  void isBounded() {
    final var bounded =
        new AuthFailureThrottle(
            AuthThrottleProperties.enforcing(MAX_FAILURES, WINDOW_SECONDS, 1),
            this.meterRegistry,
            this.ticker);

    requestFrom(CLIENT);
    for (var i = 0; i < MAX_FAILURES; i++) {
      bounded.recordFailure();
    }
    assertThatThrownBy(bounded::checkAllowed).isInstanceOf(TooManyRequestsException.class);

    requestFrom(OTHER_CLIENT);
    bounded.recordFailure();

    requestFrom(CLIENT);
    assertThatCode(bounded::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("limits nothing when disabled")
  void disabled() {
    final var disabled =
        new AuthFailureThrottle(AuthThrottleProperties.disabled(), new SimpleMeterRegistry());

    requestFrom(CLIENT);
    for (var i = 0; i < 1_000; i++) {
      disabled.recordFailure();
    }

    assertThatCode(disabled::checkAllowed).doesNotThrowAnyException();
    assertThat(disabled.isSaturated()).isFalse();
    assertThat(disabled.retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("limits nothing outside a request, where there is no client")
  void noRequestNoClient() {
    RequestContextHolder.resetRequestAttributes();

    this.fail(MAX_FAILURES * 10);

    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
    assertThat(this.throttle.isSaturated()).isFalse();
    assertThat(this.logs.list).isEmpty();
  }

  @Test
  @DisplayName("forgets every client on reset")
  void reset() {
    requestFrom(CLIENT);
    this.fail(MAX_FAILURES);

    this.throttle.reset();

    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("takes no adopted ban, and forgets nothing, when it tracks nobody or bans nobody")
  void adoptAndResetWithoutBans() {
    final var disabled =
        new AuthFailureThrottle(AuthThrottleProperties.disabled(), new SimpleMeterRegistry());
    final var noBans =
        new AuthFailureThrottle(
            AuthThrottleProperties.enforcing(MAX_FAILURES, WINDOW_SECONDS, 100, 0, 0),
            this.meterRegistry,
            this.ticker);
    final var network = ClientKeys.throttleKey(CLIENT);
    requestFrom(CLIENT);

    disabled.adopt(network, 100);
    disabled.reset();
    noBans.adopt(network, 100);
    noBans.reset();
    this.throttle.adopt(network, 0);
    this.throttle.adopt(network, -5);

    assertThatCode(disabled::checkAllowed).doesNotThrowAnyException();
    assertThatCode(noBans::checkAllowed).doesNotThrowAnyException();
    assertThatCode(this.throttle::checkAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("tells a client outside a request to retry in a second")
  void retryAfterWithoutARequest() {
    RequestContextHolder.resetRequestAttributes();

    assertThat(this.throttle.retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("does not ban a client twice while its ban runs")
  void doesNotBanTwice() {
    final var banning =
        new AuthFailureThrottle(
            AuthThrottleProperties.enforcing(MAX_FAILURES, WINDOW_SECONDS, 100, 3600, 1),
            this.meterRegistry,
            this.ticker);
    final var announced = new ArrayList<String>();
    banning.setBanListener((kind, client, banSeconds) -> announced.add(client));
    requestFrom(CLIENT);

    for (var i = 0; i < MAX_FAILURES; i++) {
      banning.recordFailure();
    }
    banning.adopt(ClientKeys.throttleKey(CLIENT), 10);
    advance(Duration.ofSeconds(WINDOW_SECONDS + 1));
    for (var i = 0; i < MAX_FAILURES; i++) {
      banning.recordFailure();
    }

    assertThat(announced).hasSize(1);
  }

  @Test
  @DisplayName("loses no failure when clients fail at the same time")
  void concurrentFailuresAreAllCounted() throws Exception {
    final var threads = 8;
    final var failuresPerThread = 100;
    final var total = threads * failuresPerThread;

    for (final var limit : List.of(total, total + 1)) {
      final var shared =
          new AuthFailureThrottle(
              AuthThrottleProperties.enforcing(limit, WINDOW_SECONDS, 100),
              this.meterRegistry,
              this.ticker);
      final var start = new CountDownLatch(1);
      final var pool = Executors.newFixedThreadPool(threads);
      final var results = new ArrayList<java.util.concurrent.Future<?>>();

      for (var t = 0; t < threads; t++) {
        results.add(
            pool.submit(
                () -> {
                  requestFrom(CLIENT);
                  start.await();
                  for (var i = 0; i < failuresPerThread; i++) {
                    shared.recordFailure();
                  }
                  RequestContextHolder.resetRequestAttributes();
                  return null;
                }));
      }

      start.countDown();
      for (final var result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
      pool.shutdown();

      requestFrom(CLIENT);
      if (limit == total) {
        assertThatThrownBy(shared::checkAllowed).isInstanceOf(TooManyRequestsException.class);
      } else {
        assertThatCode(shared::checkAllowed).doesNotThrowAnyException();
      }
    }
  }

  /**
   * {@code observe} (port of Repsy Cloud): a wrong limit or a shared client address is seen, not
   * enforced, before anyone is refused.
   */
  @Nested
  @DisplayName("observe mode")
  class ObserveMode {

    private final AuthFailureThrottle observing =
        new AuthFailureThrottle(
            AuthThrottleProperties.observing(MAX_FAILURES, WINDOW_SECONDS, 100),
            AuthFailureThrottleTest.this.meterRegistry,
            AuthFailureThrottleTest.this.ticker);

    @Test
    @DisplayName("never throws, even past the limit — this is the proof enforce is what blocks")
    void neverThrows() {
      requestFrom(CLIENT);

      for (var i = 0; i < MAX_FAILURES * 5; i++) {
        this.observing.recordFailure();
        assertThatCode(this.observing::checkAllowed).doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("still tracks failures: isSaturated turns true exactly as it would in enforce")
    void stillTracksSaturation() {
      requestFrom(CLIENT);

      final var saturationLimit = MAX_FAILURES * AuthFailureThrottle.SATURATION_FACTOR;

      for (var i = 0; i < saturationLimit; i++) {
        this.observing.recordFailure();
        this.observing.checkAllowed();
      }

      assertThat(this.observing.isSaturated()).isTrue();
    }

    @Test
    @DisplayName("counts a would-be-refused client against the metric, tagged by network")
    void countsTheWouldBlockMetric() {
      requestFrom(CLIENT);
      this.observing.recordFailure();
      this.observing.checkAllowed(); // still open (1 failure < MAX_FAILURES)

      for (var i = 1; i < MAX_FAILURES; i++) {
        this.observing.recordFailure();
      }
      // Now saturated for the window: the next checkAllowed is where enforce would refuse.
      this.observing.checkAllowed();
      this.observing.checkAllowed();

      final var counter =
          AuthFailureThrottleTest.this
              .meterRegistry
              .find(AuthFailureThrottle.WOULD_BLOCK_METRIC)
              .counter();

      assertThat(counter).isNotNull();
      assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("logs the would-be refusal once a minute per client, and counts every request")
    void wouldBlockIsLoggedOncePerMinute() {
      requestFrom(CLIENT);

      for (var i = 0; i < MAX_FAILURES; i++) {
        this.observing.recordFailure();
      }
      for (var i = 0; i < 25; i++) {
        this.observing.checkAllowed();
      }

      assertThat(
              AuthFailureThrottleTest.this.logs.list.stream()
                  .filter(event -> event.getFormattedMessage().contains("would be refused (429)"))
                  .count())
          .isEqualTo(1);
      assertThat(
              AuthFailureThrottleTest.this
                  .meterRegistry
                  .find(AuthFailureThrottle.WOULD_BLOCK_METRIC)
                  .counter()
                  .count())
          .isEqualTo(25.0);
    }

    @Test
    @DisplayName("works without a meter registry: the client is let through and only logged")
    @SuppressWarnings("unchecked")
    void worksWithoutAMeterRegistry() {
      final var noRegistry = (ObjectProvider<MeterRegistry>) mock(ObjectProvider.class);
      final var withoutMetrics =
          new AuthFailureThrottle(
              AuthThrottleProperties.observing(MAX_FAILURES, WINDOW_SECONDS, 100), noRegistry);

      requestFrom(CLIENT);

      for (var i = 0; i < MAX_FAILURES; i++) {
        withoutMetrics.recordFailure();
      }

      assertThatCode(withoutMetrics::checkAllowed).doesNotThrowAnyException();
    }
  }

  /**
   * RPS-2306: a client that used up its failures in {@code banAfterBlockedWindows} windows in a row
   * is refused for {@code banSeconds}, however its windows would have reset in the meantime.
   */
  @Nested
  @DisplayName("ban")
  class Ban {

    private static final long BAN_SECONDS = 3600;
    private static final int BAN_AFTER = 3;
    private static final Duration PAST_THE_WINDOW = Duration.ofSeconds(WINDOW_SECONDS + 1);

    private final AuthFailureThrottle banning =
        new AuthFailureThrottle(
            AuthThrottleProperties.enforcing(
                MAX_FAILURES, WINDOW_SECONDS, 100, BAN_SECONDS, BAN_AFTER),
            AuthFailureThrottleTest.this.meterRegistry,
            AuthFailureThrottleTest.this.ticker);

    private void blockedWindow() {
      for (var i = 0; i < MAX_FAILURES; i++) {
        this.banning.recordFailure();
      }
    }

    private void blockedWindows(final int count) {
      for (var i = 0; i < count; i++) {
        if (i > 0) {
          advance(PAST_THE_WINDOW);
        }
        this.blockedWindow();
      }
    }

    @Test
    @DisplayName("refuses a client that keeps going after its window would have reset")
    void refusesAfterTheWindowWouldHaveReset() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);

      advance(PAST_THE_WINDOW);

      assertThatThrownBy(this.banning::checkAllowed)
          .isInstanceOfSatisfying(
              TooManyRequestsException.class,
              e ->
                  assertThat(e.getRetryAfterSeconds())
                      .isEqualTo(BAN_SECONDS - PAST_THE_WINDOW.toSeconds()));
      assertThat(this.banning.retryAfterSeconds())
          .isEqualTo(BAN_SECONDS - PAST_THE_WINDOW.toSeconds());
    }

    @Test
    @DisplayName("announces a new ban once, and refuses a client for an adopted ban")
    void announcesAndAdopts() {
      final var announced = new java.util.ArrayList<String>();
      this.banning.setBanListener(
          (kind, client, banSeconds) -> announced.add(kind + ":" + banSeconds));
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);
      this.blockedWindow();

      assertThat(announced).containsExactly("AUTH:" + BAN_SECONDS);

      final var other =
          new AuthFailureThrottle(
              AuthThrottleProperties.enforcing(
                  MAX_FAILURES, WINDOW_SECONDS, 100, BAN_SECONDS, BAN_AFTER),
              AuthFailureThrottleTest.this.meterRegistry,
              AuthFailureThrottleTest.this.ticker);
      other.setBanListener((kind, client, banSeconds) -> announced.add("adopted"));
      other.adopt(ClientKeys.throttleKey(CLIENT), 100);

      assertThatThrownBy(other::checkAllowed)
          .isInstanceOfSatisfying(
              TooManyRequestsException.class,
              e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(100));
      assertThat(announced).hasSize(1);
    }

    @Test
    @DisplayName("lets the client in again when the ban has run out")
    void endsWhenTheBanHasRunOut() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);

      advance(Duration.ofSeconds(BAN_SECONDS - 1));
      assertThatThrownBy(this.banning::checkAllowed).isInstanceOf(TooManyRequestsException.class);

      advance(Duration.ofSeconds(1));
      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not ban a client that used up its failures in fewer windows in a row")
    void doesNotBanBeforeTheLastBlockedWindow() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER - 1);

      advance(PAST_THE_WINDOW);

      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a window that skipped a whole window of the client breaks the run")
    void aSkippedWindowBreaksTheRun() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER - 1);

      advance(PAST_THE_WINDOW.multipliedBy(2));
      this.blockedWindow();

      advance(PAST_THE_WINDOW);
      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a window that stayed under the limit breaks the run")
    void aQuietWindowBreaksTheRun() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER - 1);

      advance(PAST_THE_WINDOW);
      this.banning.recordFailure();

      advance(PAST_THE_WINDOW);
      this.blockedWindow();

      advance(PAST_THE_WINDOW);
      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("bans the client and nobody else, and treats a banned client as saturated")
    void bansOnlyTheClient() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);
      advance(PAST_THE_WINDOW);

      assertThat(this.banning.isSaturated()).isTrue();

      requestFrom(OTHER_CLIENT);
      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
      assertThat(this.banning.isSaturated()).isFalse();

      advance(Duration.ofSeconds(BAN_SECONDS));
      requestFrom(CLIENT);
      assertThat(this.banning.isSaturated()).isFalse();
    }

    @Test
    @DisplayName("bans an IPv6 client by its network")
    void bansTheNetworkOfAnIpv6Client() {
      requestFrom(IPV6_CLIENT);
      this.blockedWindows(BAN_AFTER);
      advance(PAST_THE_WINDOW);

      requestFrom(IPV6_CLIENT_SAME_NETWORK);
      assertThatThrownBy(this.banning::checkAllowed).isInstanceOf(TooManyRequestsException.class);

      requestFrom(IPV6_CLIENT_OTHER_NETWORK);
      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logs the ban once, with the address and the network")
    void logsTheBanOnce() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);
      this.banning.recordFailure();

      final var banLines =
          AuthFailureThrottleTest.this.logs.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .filter(message -> message.contains("windows in a row"))
              .toList();

      assertThat(banLines).hasSize(1);
      assertThat(banLines.getFirst()).contains(CLIENT).contains("banning it for 3600s");
    }

    @Test
    @DisplayName("with ban-seconds 0 a client that keeps going is only ever held to its window")
    void banSecondsZeroTurnsTheBanOff() {
      final var withoutBan =
          new AuthFailureThrottle(
              AuthThrottleProperties.enforcing(MAX_FAILURES, WINDOW_SECONDS, 100, 0, BAN_AFTER),
              AuthFailureThrottleTest.this.meterRegistry,
              AuthFailureThrottleTest.this.ticker);

      requestFrom(CLIENT);

      for (var window = 0; window < BAN_AFTER + 2; window++) {
        for (var i = 0; i < MAX_FAILURES; i++) {
          withoutBan.recordFailure();
        }
        advance(PAST_THE_WINDOW);
      }

      assertThatCode(withoutBan::checkAllowed).doesNotThrowAnyException();
      assertThat(withoutBan.isSaturated()).isFalse();
    }

    @Test
    @DisplayName("in observe a banned client is let through, logged and counted")
    void observeLetsABannedClientThrough() {
      final var observing =
          new AuthFailureThrottle(
              new AuthThrottleProperties(
                  AuthThrottleMode.OBSERVE,
                  null,
                  MAX_FAILURES,
                  WINDOW_SECONDS,
                  100L,
                  BAN_SECONDS,
                  BAN_AFTER),
              AuthFailureThrottleTest.this.meterRegistry,
              AuthFailureThrottleTest.this.ticker);

      requestFrom(CLIENT);

      for (var window = 0; window < BAN_AFTER; window++) {
        if (window > 0) {
          advance(PAST_THE_WINDOW);
        }
        for (var i = 0; i < MAX_FAILURES; i++) {
          observing.recordFailure();
        }
      }
      advance(PAST_THE_WINDOW);

      assertThatCode(observing::checkAllowed).doesNotThrowAnyException();

      assertThat(
              AuthFailureThrottleTest.this
                  .meterRegistry
                  .find(AuthFailureThrottle.WOULD_BLOCK_METRIC)
                  .counter())
          .isNotNull();
      assertThat(
              AuthFailureThrottleTest.this.logs.list.stream()
                  .map(ILoggingEvent::getFormattedMessage)
                  .anyMatch(m -> m.contains("because it is banned")))
          .isTrue();
    }

    @Test
    @DisplayName("reset forgets the bans too")
    void resetForgetsTheBans() {
      requestFrom(CLIENT);
      this.blockedWindows(BAN_AFTER);
      advance(PAST_THE_WINDOW);

      this.banning.reset();

      assertThatCode(this.banning::checkAllowed).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("binds the settings from repsy.security.auth-throttle")
  void bindsProperties() {
    final var source =
        new MapConfigurationPropertySource(
            java.util.Map.of(
                "repsy.security.auth-throttle.mode", "enforce",
                "repsy.security.auth-throttle.max-failures", "5",
                "repsy.security.auth-throttle.window-seconds", "30",
                "repsy.security.auth-throttle.max-clients", "50"));

    final var bound =
        new Binder(source).bind("repsy.security.auth-throttle", AuthThrottleProperties.class);

    assertThat(bound.get()).isEqualTo(AuthThrottleProperties.enforcing(5, 30, 50));
  }

  @Test
  @DisplayName("defaults to enforce when the mode is not set at all")
  void defaultsToEnforce() {
    assertThat(new AuthThrottleProperties(null, null, 5, 30L, 50L, null, null).mode())
        .isEqualTo(AuthThrottleMode.ENFORCE);
  }

  @Test
  @DisplayName("the legacy enabled=false switches the throttle off whatever the mode says")
  void legacyEnabledFalseMeansOff() {
    assertThat(
            new AuthThrottleProperties(AuthThrottleMode.ENFORCE, false, 5, 30L, 50L, null, null)
                .mode())
        .isEqualTo(AuthThrottleMode.OFF);
    assertThat(new AuthThrottleProperties(null, true, 5, 30L, 50L, null, null).mode())
        .isEqualTo(AuthThrottleMode.ENFORCE);
  }

  @Test
  @DisplayName("refuses settings that could not hold a window, unless the mode is off")
  void rejectsNonPositiveSettings() {
    for (final var mode : List.of(AuthThrottleMode.OBSERVE, AuthThrottleMode.ENFORCE)) {
      assertThatThrownBy(() -> new AuthThrottleProperties(mode, null, 0, 60L, 10L, null, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new AuthThrottleProperties(mode, null, 10, 0L, 10L, null, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new AuthThrottleProperties(mode, null, 10, 60L, 0L, null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(AuthThrottleProperties.disabled().mode()).isEqualTo(AuthThrottleMode.OFF);
  }
}
