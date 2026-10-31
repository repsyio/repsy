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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 *       neither a live deploy token nor a verifiable protocol JWT (RPS-1209); an expired but
 *       validly signed JWT is recognized and does not count. A success never resets the count, so a
 *       client cannot clear it by logging in with an account of its own; only the end of the window
 *       does.
 *   <li>A fixed window per client: after {@code maxFailures} failures the next password check would
 *       be refused. In {@link AuthThrottleMode#ENFORCE} (the default) it is refused, with {@code
 *       429} and {@code Retry-After}; in {@link AuthThrottleMode#OBSERVE} it is logged and counted
 *       against {@value #WOULD_BLOCK_METRIC} instead, and let through exactly as {@link
 *       AuthThrottleMode#OFF} would. A refused (or would-refuse) attempt costs no BCrypt, but it is
 *       counted too, so a client that keeps sending guesses reaches {@link #isSaturated()
 *       saturation} after {@value #SATURATION_FACTOR} times the limit.
 *   <li>A client that keeps going is banned (RPS-2306): one that has used up its failures in {@code
 *       banAfterBlockedWindows} windows in a row is refused outright for {@code banSeconds},
 *       whatever its windows say, so it no longer gets a fresh {@code maxFailures} checks every
 *       minute. A banned client costs no BCrypt, its remembered password stops working like a
 *       saturated client's, and a success does not lift the ban. The ban follows the mode like the
 *       window does. It is held in memory, per instance, and a restart clears it.
 *   <li>The clients are held in bounded caches, so an attacker cannot fill the memory with
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
  public static final int SATURATION_FACTOR = 10;

  /** Counted in {@link AuthThrottleMode#OBSERVE} for a client that {@code enforce} would refuse. */
  static final String WOULD_BLOCK_METRIC = "repsy.auth.throttle_would_block";

  private final @Nullable Cache<String, Window> windows;
  private final @Nullable Cache<String, Long> bans;
  private final AuthThrottleMode mode;
  private final Supplier<@Nullable MeterRegistry> meterRegistry;
  private final Ticker ticker;
  private final long maxFailures;
  private final long saturationLimit;
  private final long windowNanos;
  private final long banNanos;
  private final LogGate bannedLog;
  private final LogGate windowLog;
  private final AtomicReference<@Nullable ClientBanListener> banListener = new AtomicReference<>();
  private final int banAfterBlockedWindows;

  /**
   * The registry is optional: without Micrometer the {@link AuthThrottleMode#OBSERVE} counter is
   * skipped and the log line remains.
   */
  @Autowired
  public AuthFailureThrottle(
      final AuthThrottleProperties properties, final ObjectProvider<MeterRegistry> meterRegistry) {

    this(properties, meterRegistry::getIfAvailable, Ticker.systemTicker());
  }

  public AuthFailureThrottle(
      final AuthThrottleProperties properties, final MeterRegistry meterRegistry) {

    this(properties, () -> meterRegistry, Ticker.systemTicker());
  }

  @VisibleForTesting
  AuthFailureThrottle(
      final AuthThrottleProperties properties,
      final MeterRegistry meterRegistry,
      final Ticker ticker) {

    this(properties, () -> meterRegistry, ticker);
  }

  private AuthFailureThrottle(
      final AuthThrottleProperties properties,
      final Supplier<@Nullable MeterRegistry> meterRegistry,
      final Ticker ticker) {

    this.ticker = ticker;
    this.mode = properties.mode();
    this.meterRegistry = meterRegistry;
    this.maxFailures = properties.maxFailures();
    this.saturationLimit = properties.maxFailures() * (long) SATURATION_FACTOR;
    this.windowNanos = TimeUnit.SECONDS.toNanos(properties.windowSeconds());
    this.banNanos = TimeUnit.SECONDS.toNanos(properties.banSeconds());
    this.bannedLog = new LogGate(ticker, properties.maxClients());
    this.windowLog = new LogGate(ticker, properties.maxClients());
    this.banAfterBlockedWindows = properties.banAfterBlockedWindows();

    final var tracksClients = this.mode != AuthThrottleMode.OFF;
    final var bansClients = tracksClients && properties.banSeconds() > 0;

    // A ban counts windows in a row, so the previous window has to outlive its own end by one more.
    final var windowRetention =
        Duration.ofSeconds(properties.windowSeconds() * (bansClients ? 2 : 1));

    this.windows =
        tracksClients
            ? CacheBuilder.newBuilder()
                .maximumSize(properties.maxClients())
                .expireAfterWrite(windowRetention)
                .ticker(ticker)
                .build()
            : null;

    this.bans =
        bansClients
            ? CacheBuilder.newBuilder()
                .maximumSize(properties.maxClients())
                .expireAfterWrite(Duration.ofSeconds(properties.banSeconds()))
                .ticker(ticker)
                .build()
            : null;
  }

  /**
   * Refuses the request when its client has used up its failures for the current window and the
   * mode is {@link AuthThrottleMode#ENFORCE}. In {@link AuthThrottleMode#OBSERVE} the same client
   * is logged and counted instead of refused; in {@link AuthThrottleMode#OFF} nothing is tracked at
   * all.
   *
   * @throws TooManyRequestsException when the client is blocked and the mode is {@code enforce}
   */
  public void checkAllowed() {

    final var client = ClientKeys.currentClient();

    if (this.windows == null || client == null) {
      return;
    }

    final var now = this.now();
    final var banSecondsLeft = this.banSecondsLeft(client, now);

    if (banSecondsLeft > 0) {
      this.refuseBanned(client, banSecondsLeft);
    } else {
      this.checkWindow(this.windows, client, now);
    }
  }

  private void checkWindow(
      final Cache<String, Window> clientWindows, final String client, final long now) {

    final var window = clientWindows.getIfPresent(client);

    if (window == null || this.isOpen(window, now)) {
      return;
    }

    // The refused (or would-refuse) attempt is counted, see SATURATION_FACTOR.
    clientWindows.asMap().computeIfPresent(client, (key, current) -> this.next(current, now));

    if (this.mode == AuthThrottleMode.OBSERVE) {
      if (this.windowLog.allows(client)) {
        log.info(
            "repsy.security.auth-throttle.mode=observe: client {} would be refused (429) for {}"
                + " failed password checks; letting it through (logged once a minute per client,"
                + " every request is counted in {})",
            client,
            this.maxFailures,
            WOULD_BLOCK_METRIC);
      }

      this.countWouldBlock(client);
      return;
    }

    throw new TooManyRequestsException(this.secondsLeft(window, now));
  }

  /** Refuses a banned client, or in {@code observe} only logs and counts it. */
  private void refuseBanned(final String client, final long banSecondsLeft) {

    if (this.mode != AuthThrottleMode.OBSERVE) {
      throw new TooManyRequestsException(banSecondsLeft);
    }

    if (this.bannedLog.allows(client)) {
      log.warn(
          "repsy.security.auth-throttle.mode=observe: client {} would be refused (429) for"
              + " another {}s because it is banned; letting it through (logged once a minute per"
              + " client, every request is counted in {})",
          client,
          banSecondsLeft,
          WOULD_BLOCK_METRIC);
    }

    this.countWouldBlock(client);
  }

  private void countWouldBlock(final String client) {

    final var registry = this.meterRegistry.get();

    if (registry != null) {
      registry.counter(WOULD_BLOCK_METRIC, "network", client).increment();
    }
  }

  /** Where new bans are announced, for instances that share them (RPS-2324). */
  @Autowired(required = false)
  public void setBanListener(final @Nullable ClientBanListener banListener) {

    this.banListener.set(banListener);
  }

  /**
   * Takes a ban that another instance announced. It does not announce it again, and an existing ban
   * of the client stays as it is.
   */
  public void adopt(final String client, final long banSecondsLeft) {

    if (this.bans == null || banSecondsLeft <= 0) {
      return;
    }

    final var leftNanos = Math.min(TimeUnit.SECONDS.toNanos(banSecondsLeft), this.banNanos);

    this.bans.asMap().putIfAbsent(client, this.now() - (this.banNanos - leftNanos));
  }

  /** Counts one failed BCrypt check for the client of the current request. */
  public void recordFailure() {

    final var remoteAddr = ClientKeys.currentRemoteAddr();
    final var client = remoteAddr == null ? null : ClientKeys.throttleKey(remoteAddr);

    if (this.windows == null || client == null) {
      return;
    }

    final var now = this.now();
    final var window =
        this.windows.asMap().compute(client, (key, current) -> this.next(current, now));

    if (window.failures() == this.maxFailures) {
      // Once per window, because the count only grows. The address is logged individually, even
      // though IPv6 clients are throttled per network, so an operator can still see it.
      log.info(
          "Client {} (network {}) made {} failed password checks ({})",
          remoteAddr,
          client,
          this.maxFailures,
          this.mode == AuthThrottleMode.ENFORCE
              ? "refusing its password checks until its window ends"
              : "would be refused if repsy.security.auth-throttle.mode were enforce");
      this.banIfRepeated(client, remoteAddr, window, now);
    }
  }

  /** Starts the ban of a client whose window just ran out, if enough windows in a row did. */
  private void banIfRepeated(
      final String client, final @Nullable String remoteAddr, final Window window, final long now) {

    if (this.bans == null || window.precedingBlocked() + 1 < this.banAfterBlockedWindows) {
      return;
    }

    if (this.bans.asMap().putIfAbsent(client, now) == null) {
      log.warn(
          "Client {} (network {}) used up its failed password checks in {} windows in a row ({})",
          remoteAddr,
          client,
          this.banAfterBlockedWindows,
          this.mode == AuthThrottleMode.ENFORCE
              ? "banning it for " + TimeUnit.NANOSECONDS.toSeconds(this.banNanos) + "s"
              : "would be banned if repsy.security.auth-throttle.mode were enforce");
      this.announce(client);
    }
  }

  private void announce(final String client) {

    final var listener = this.banListener.get();

    if (listener == null) {
      return;
    }

    try {
      listener.banned(BanKind.AUTH, client, TimeUnit.NANOSECONDS.toSeconds(this.banNanos));
    } catch (final RuntimeException e) {
      log.warn("The ban of client {} could not be shared with the other instances", client, e);
    }
  }

  /**
   * Tells whether the client of the current request has kept guessing after it was blocked (or
   * would have been, in {@code observe}), so that not even a remembered password is let through any
   * more.
   */
  public boolean isSaturated() {

    final var client = ClientKeys.currentClient();

    if (this.windows == null || client == null) {
      return false;
    }

    final var now = this.now();

    if (this.banSecondsLeft(client, now) > 0) {
      return true;
    }

    final var window = this.windows.getIfPresent(client);

    return window != null
        && !this.expired(window, now)
        && window.failures() >= this.saturationLimit;
  }

  /**
   * Seconds until the client of the current request is let in again: the rest of its ban, or the
   * rest of its window, at least 1.
   */
  public long retryAfterSeconds() {

    final var client = ClientKeys.currentClient();
    final var now = this.now();
    final var banSecondsLeft = client == null ? 0 : this.banSecondsLeft(client, now);

    if (banSecondsLeft > 0) {
      return banSecondsLeft;
    }

    final var window =
        this.windows == null || client == null ? null : this.windows.getIfPresent(client);

    return window == null ? 1 : this.secondsLeft(window, now);
  }

  /** Forgets every client. Only tests need it: their requests all come from one address. */
  @VisibleForTesting
  public void reset() {

    if (this.windows != null) {
      this.windows.invalidateAll();
    }

    if (this.bans != null) {
      this.bans.invalidateAll();
    }
  }

  @VisibleForTesting
  public long now() {

    return this.ticker.read();
  }

  private boolean isOpen(final Window window, final long now) {

    return this.expired(window, now) || window.failures() < this.maxFailures;
  }

  private boolean expired(final Window window, final long now) {

    return now - window.startNanos() >= this.windowNanos;
  }

  /** The window after one more attempt, or a new window when the old one has ended. */
  private Window next(final @Nullable Window current, final long now) {

    if (current == null) {
      return new Window(now, 1, 0);
    }

    if (this.expired(current, now)) {
      return new Window(now, 1, this.precedingBlocked(current, now));
    }

    return new Window(
        current.startNanos(),
        Math.min(current.failures() + 1, this.saturationLimit),
        current.precedingBlocked());
  }

  /**
   * How many windows in a row, ending with {@code ended}, the client used up its failures in. The
   * run is broken when the client skipped a whole window since.
   */
  private int precedingBlocked(final Window ended, final long now) {

    final var wasBlocked = ended.failures() >= this.maxFailures;
    final var isConsecutive = now - ended.startNanos() < 2 * this.windowNanos;

    return wasBlocked && isConsecutive
        ? Math.min(ended.precedingBlocked() + 1, this.banAfterBlockedWindows)
        : 0;
  }

  /** Seconds left of the client's ban, rounded up; 0 when it is not banned. */
  private long banSecondsLeft(final String client, final long now) {

    final var bannedAt = this.bans == null ? null : this.bans.getIfPresent(client);
    final var leftNanos = bannedAt == null ? 0 : this.banNanos - (now - bannedAt);

    return leftNanos <= 0 ? 0 : secondsUntil(leftNanos);
  }

  private long secondsLeft(final Window window, final long now) {

    return secondsUntil(this.windowNanos - (now - window.startNanos()));
  }

  private static long secondsUntil(final long leftNanos) {

    return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(leftNanos + TimeUnit.SECONDS.toNanos(1) - 1));
  }

  /**
   * The failures of one client since {@code startNanos}, and how many windows in a row before this
   * one the client used up its failures in (capped at {@code banAfterBlockedWindows}).
   */
  private record Window(long startNanos, long failures, int precedingBlocked) {}
}
