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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.base.Ticker;
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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

  private final AuthFailureThrottle throttle =
      new AuthFailureThrottle(
          new AuthThrottleProperties(true, MAX_FAILURES, WINDOW_SECONDS, 100), this.ticker);

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
    assertThat(event.getLevel().toString()).isEqualTo("WARN");
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
            new AuthThrottleProperties(true, MAX_FAILURES, WINDOW_SECONDS, 1), this.ticker);

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
    final var disabled = new AuthFailureThrottle(AuthThrottleProperties.disabled());

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
  @DisplayName("loses no failure when clients fail at the same time")
  void concurrentFailuresAreAllCounted() throws Exception {
    final var threads = 8;
    final var failuresPerThread = 100;
    final var total = threads * failuresPerThread;

    for (final var limit : List.of(total, total + 1)) {
      final var shared =
          new AuthFailureThrottle(
              new AuthThrottleProperties(true, limit, WINDOW_SECONDS, 100), this.ticker);
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

  @Test
  @DisplayName("binds the settings from repsy.security.auth-throttle")
  void bindsProperties() {
    final var source =
        new MapConfigurationPropertySource(
            java.util.Map.of(
                "repsy.security.auth-throttle.enabled", "true",
                "repsy.security.auth-throttle.max-failures", "5",
                "repsy.security.auth-throttle.window-seconds", "30",
                "repsy.security.auth-throttle.max-clients", "50"));

    final var bound =
        new Binder(source).bind("repsy.security.auth-throttle", AuthThrottleProperties.class);

    assertThat(bound.get()).isEqualTo(new AuthThrottleProperties(true, 5, 30, 50));
  }

  @Test
  @DisplayName("refuses settings that could not hold a window")
  void rejectsNonPositiveSettings() {
    assertThatThrownBy(() -> new AuthThrottleProperties(true, 0, 60, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthThrottleProperties(true, 10, 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthThrottleProperties(true, 10, 60, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AuthThrottleProperties(false, 0, 0, 0).enabled()).isFalse();
  }
}
