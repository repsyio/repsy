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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Limits the failed password checks of one client, so a flood of wrong credentials cannot keep the
 * CPU busy with BCrypt (RPS-1092). A failed check is never cached (RPS-1025) and costs one full
 * BCrypt verification, on the protocol routes and on the panel login alike.
 *
 * <p>How it counts:
 *
 * <ul>
 *   <li>The client is the remote address of the request. Behind a reverse proxy that is the address
 *       Tomcat's {@code RemoteIpValve} took from {@code X-Forwarded-For}, and only when the direct
 *       peer is a trusted proxy ({@code server.forward-headers-strategy: native}), so a client
 *       cannot pick its own key by sending the header. For IPv4 the whole address is the key; for
 *       IPv6 the key is the address's {@code /64} network, since a single subscriber or site
 *       normally holds a whole {@code /64} and could otherwise get a fresh count for every address
 *       it uses (RPS-1164).
 *   <li>The key is the client and never the username. An unknown username, a wrong password and a
 *       known user all count the same and are refused the same way, so the throttle reveals nothing
 *       about which usernames exist (RPS-906).
 *   <li>Only failed credential checks count: a failed BCrypt check, and a Bearer value that is
 *       neither a live deploy token nor a verifiable protocol JWT (RPS-1209). A success never
 *       resets the count, so a client cannot clear it by logging in with an account of its own;
 *       only the end of the window does.
 *   <li>A fixed window per client: after {@code maxFailures} failures the next password check is
 *       refused until the window ends. A refused attempt costs no BCrypt, but it is counted too, so
 *       a client that keeps sending guesses reaches {@link #isSaturated() saturation} after {@value
 *       #SATURATION_FACTOR} times the limit.
 *   <li>The clients are held in a bounded cache, so an attacker cannot fill the memory with
 *       addresses.
 * </ul>
 *
 * <p>Outside a request (no request context) nothing is limited.
 */
@Slf4j
@Component
public class AuthFailureThrottle {

  /**
   * A blocked client whose remembered credentials still pass would be an unmetered oracle for
   * guessing the password of a user whose credentials are remembered: a right guess passes, a wrong
   * one is refused at the price of a hash lookup. Refused attempts therefore count, and past this
   * multiple of the limit the exemption closes too.
   */
  static final int SATURATION_FACTOR = 10;

  private final @Nullable Cache<String, Window> windows;
  private final @NonNull Ticker ticker;
  private final long maxFailures;
  private final long saturationLimit;
  private final long windowNanos;

  @Autowired
  public AuthFailureThrottle(final @NonNull AuthThrottleProperties properties) {

    this(properties, Ticker.systemTicker());
  }

  @VisibleForTesting
  AuthFailureThrottle(
      final @NonNull AuthThrottleProperties properties, final @NonNull Ticker ticker) {

    this.ticker = ticker;
    this.maxFailures = properties.maxFailures();
    this.saturationLimit = properties.maxFailures() * (long) SATURATION_FACTOR;
    this.windowNanos = TimeUnit.SECONDS.toNanos(properties.windowSeconds());

    this.windows =
        properties.enabled()
            ? CacheBuilder.newBuilder()
                .maximumSize(properties.maxClients())
                .expireAfterWrite(Duration.ofSeconds(properties.windowSeconds()))
                .ticker(ticker)
                .build()
            : null;
  }

  /**
   * Refuses the request when its client has used up its failures for the current window.
   *
   * @throws TooManyRequestsException when the client is blocked
   */
  public void checkAllowed() {

    final var client = currentClient();

    if (this.windows == null || client == null) {
      return;
    }

    final var now = this.now();
    final var window = this.windows.getIfPresent(client);

    if (window == null || this.isOpen(window, now)) {
      return;
    }

    // The refused attempt is counted, see SATURATION_FACTOR.
    this.windows.asMap().computeIfPresent(client, (key, current) -> this.next(current, now));

    throw new TooManyRequestsException(this.secondsLeft(window, now));
  }

  /** Counts one failed BCrypt check for the client of the current request. */
  public void recordFailure() {

    final var remoteAddr = currentRemoteAddr();
    final var client = remoteAddr == null ? null : throttleKey(remoteAddr);

    if (this.windows == null || client == null) {
      return;
    }

    final var now = this.now();
    final var window =
        this.windows.asMap().compute(client, (key, current) -> this.next(current, now));

    if (window.failures() == this.maxFailures) {
      // Once per window, because the count only grows. The address is logged individually, even
      // though IPv6 clients are throttled per network, so an operator can still see it.
      log.warn(
          "Client {} (network {}) made {} failed password checks, refusing its password checks"
              + " until its window ends",
          remoteAddr,
          client,
          this.maxFailures);
    }
  }

  /**
   * Tells whether the client of the current request has kept guessing after it was blocked, so that
   * not even a remembered password is let through any more.
   */
  public boolean isSaturated() {

    final var client = currentClient();

    if (this.windows == null || client == null) {
      return false;
    }

    final var window = this.windows.getIfPresent(client);

    return window != null
        && !this.expired(window, this.now())
        && window.failures() >= this.saturationLimit;
  }

  /** Seconds until the client of the current request starts a new window, at least 1. */
  public long retryAfterSeconds() {

    final var client = currentClient();
    final var window =
        this.windows == null || client == null ? null : this.windows.getIfPresent(client);

    return window == null ? 1 : this.secondsLeft(window, this.now());
  }

  /** Forgets every client. Only tests need it: their requests all come from one address. */
  @VisibleForTesting
  public void reset() {

    if (this.windows != null) {
      this.windows.invalidateAll();
    }
  }

  @VisibleForTesting
  long now() {

    return this.ticker.read();
  }

  private boolean isOpen(final @NonNull Window window, final long now) {

    return this.expired(window, now) || window.failures() < this.maxFailures;
  }

  private boolean expired(final @NonNull Window window, final long now) {

    return now - window.startNanos() >= this.windowNanos;
  }

  /** The window after one more attempt, or a new window when the old one has ended. */
  private @NonNull Window next(final @Nullable Window current, final long now) {

    if (current == null || this.expired(current, now)) {
      return new Window(now, 1);
    }

    return new Window(current.startNanos(), Math.min(current.failures() + 1, this.saturationLimit));
  }

  private long secondsLeft(final @NonNull Window window, final long now) {

    final var leftNanos = this.windowNanos - (now - window.startNanos());

    return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(leftNanos + TimeUnit.SECONDS.toNanos(1) - 1));
  }

  private static @Nullable String currentClient() {

    final var remoteAddr = currentRemoteAddr();

    return remoteAddr == null ? null : throttleKey(remoteAddr);
  }

  private static @Nullable String currentRemoteAddr() {

    return RequestContextHolder.getRequestAttributes()
            instanceof final ServletRequestAttributes attributes
        ? attributes.getRequest().getRemoteAddr()
        : null;
  }

  /**
   * The throttle key for one remote address: the address itself for IPv4, or its {@code /64}
   * network for IPv6, so an attacker (or a legitimate NAT'd network) cannot evade the throttle by
   * cycling through the addresses of one network (RPS-1164). Parsing is strict (a literal address,
   * no DNS lookup); an address {@link InetAddresses#forString} cannot parse is throttled under its
   * own literal text, which only happens for a remote address Tomcat did not hand us as a literal
   * IP in the first place.
   */
  private static @NonNull String throttleKey(final @NonNull String remoteAddr) {

    final InetAddress address;

    try {
      address = InetAddresses.forString(remoteAddr);
    } catch (final IllegalArgumentException notALiteralAddress) {
      return remoteAddr;
    }

    if (address instanceof final Inet6Address inet6) {
      final var network = Arrays.copyOf(inet6.getAddress(), 8);

      return HexFormat.of().formatHex(network);
    }

    return remoteAddr;
  }

  /** The failures of one client since {@code startNanos}. */
  private record Window(long startNanos, long failures) {}
}
