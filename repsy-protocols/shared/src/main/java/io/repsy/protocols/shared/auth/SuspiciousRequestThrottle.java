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
import com.google.common.net.InetAddresses;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bans the clients that send probes (RPS-2319): path traversal, secret hunting and the other
 * requests {@link SuspiciousRequestClassifier} recognises. It is the counterpart of {@link
 * AuthFailureThrottle} for requests that are not logins.
 *
 * <ul>
 *   <li>The client is the one of {@link ClientKeys}: the remote address (IPv6 by {@code /64}),
 *       which behind a trusted proxy is the address Tomcat took from {@code X-Forwarded-For}.
 *   <li>Every signal of a request weighs {@link SuspiciousRequestProperties#weight its weight} in
 *       strikes. A client that collects {@code maxStrikes} in one fixed window is banned for {@code
 *       banSeconds}; a single request that weighs that much bans at once. Scanners send bursts, so
 *       there is no "windows in a row" rule as there is for logins.
 *   <li>In {@link AuthThrottleMode#ENFORCE} the {@code SuspiciousRequestFilter} refuses a banned
 *       client with 429 on every path. In {@link AuthThrottleMode#OBSERVE} the same client is
 *       tracked and banned in the books, and only logged and counted against {@value
 *       #WOULD_BLOCK_METRIC}.
 *   <li>Clients are held in bounded in-memory caches, per instance; a restart clears them.
 * </ul>
 */
@Slf4j
@Component
public class SuspiciousRequestThrottle {

  /**
   * Counted in {@link AuthThrottleMode#OBSERVE} for a banned client that {@code enforce} refuses.
   */
  static final String WOULD_BLOCK_METRIC = "repsy.security.probe_would_block";

  static final String STRIKES_METRIC = "repsy.security.probe_strikes";
  static final String BANS_METRIC = "repsy.security.probe_bans";

  private final SuspiciousRequestProperties properties;
  private final Supplier<@Nullable MeterRegistry> meterRegistry;
  private final Ticker ticker;
  private final List<Cidr> allowlist;
  private final @Nullable Cache<String, Window> windows;
  private final @Nullable Cache<String, Long> bans;
  private final long windowNanos;
  private final long banNanos;
  private volatile @Nullable ClientBanListener banListener;

  @Autowired
  public SuspiciousRequestThrottle(
      final SuspiciousRequestProperties properties,
      final ObjectProvider<MeterRegistry> meterRegistry) {

    this(properties, meterRegistry::getIfAvailable, Ticker.systemTicker());
  }

  @VisibleForTesting
  public SuspiciousRequestThrottle(
      final SuspiciousRequestProperties properties,
      final MeterRegistry meterRegistry,
      final Ticker ticker) {

    this(properties, () -> meterRegistry, ticker);
  }

  private SuspiciousRequestThrottle(
      final SuspiciousRequestProperties properties,
      final Supplier<@Nullable MeterRegistry> meterRegistry,
      final Ticker ticker) {

    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.ticker = ticker;
    this.allowlist = properties.allowlist().stream().map(Cidr::parse).toList();
    this.windowNanos = TimeUnit.SECONDS.toNanos(properties.windowSeconds());
    this.banNanos = TimeUnit.SECONDS.toNanos(properties.banSeconds());

    if (properties.mode() == AuthThrottleMode.OFF) {
      this.windows = null;
      this.bans = null;
    } else {
      this.windows =
          CacheBuilder.newBuilder()
              .maximumSize(properties.maxClients())
              .expireAfterWrite(Duration.ofSeconds(properties.windowSeconds()))
              .ticker(ticker)
              .build();
      this.bans =
          CacheBuilder.newBuilder()
              .maximumSize(properties.maxClients())
              .expireAfterWrite(Duration.ofSeconds(properties.banSeconds()))
              .ticker(ticker)
              .build();
    }
  }

  /** Where new bans are announced, for instances that share them (RPS-2324). */
  @Autowired(required = false)
  public void setBanListener(final @Nullable ClientBanListener banListener) {

    this.banListener = banListener;
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

    this.bans.asMap().putIfAbsent(client, this.ticker.read() - (this.banNanos - leftNanos));
  }

  public AuthThrottleMode mode() {

    return this.properties.mode();
  }

  /** Whether the request is classified at all: not in mode {@code off}. */
  public boolean isActive() {

    return this.windows != null;
  }

  /** Whether the remote address is in the allowlist, so its requests are never counted. */
  public boolean isAllowlisted(final String remoteAddr) {

    if (this.allowlist.isEmpty()) {
      return false;
    }

    final InetAddress address;

    try {
      address = InetAddresses.forString(remoteAddr);
    } catch (final IllegalArgumentException notALiteralAddress) {
      return false;
    }

    return this.allowlist.stream().anyMatch(cidr -> cidr.contains(address));
  }

  /** Seconds left of the client's ban, rounded up; 0 when it is not banned. */
  public long banSecondsLeft(final String client) {

    final var bannedAt = this.bans == null ? null : this.bans.getIfPresent(client);
    final var leftNanos = bannedAt == null ? 0 : this.banNanos - (this.ticker.read() - bannedAt);

    return leftNanos <= 0
        ? 0
        : Math.max(1, TimeUnit.NANOSECONDS.toSeconds(leftNanos + TimeUnit.SECONDS.toNanos(1) - 1));
  }

  /** Counts a refused (or in {@code observe} would-be refused) request of a banned client. */
  public void countWouldBlock(final String client, final long banSecondsLeft) {

    log.warn(
        "repsy.security.probe-throttle.mode=observe: client {} would be refused (429) for"
            + " another {}s because it is banned; letting it through",
        client,
        banSecondsLeft);
    this.count(WOULD_BLOCK_METRIC);
  }

  /**
   * Adds the strikes of one suspicious request and bans the client when its window is full.
   *
   * @return the client's ban seconds left after this request, 0 when it is not banned
   */
  public long record(
      final String client, final String remoteAddr, final Set<SignalFamily> signals) {

    if (this.windows == null || this.bans == null || signals.isEmpty()) {
      return 0;
    }

    final var added = this.weightOf(signals, true);
    final var soft = this.weightOf(signals, false);

    signals.forEach(family -> this.count(STRIKES_METRIC, family));

    final var now = this.ticker.read();
    final var window =
        this.windows
            .asMap()
            .compute(client, (key, current) -> this.next(current, now, added, soft));

    log.debug(
        "Client {} sent a suspicious request {}: {} strikes", remoteAddr, signals, window.total());

    if (window.bans(this.properties.maxStrikes())) {
      this.ban(this.bans, client, remoteAddr, signals, now);
    } else if (window.total() == added + soft) {
      // The first request of a window, once per window and client.
      log.info("Client {} (network {}) sent a suspicious request {}", remoteAddr, client, signals);
    }

    return this.banSecondsLeft(client);
  }

  /** The strikes of the signals that can (or, with {@code false}, cannot) ban a client alone. */
  private int weightOf(final Set<SignalFamily> signals, final boolean bansAlone) {

    return signals.stream()
        .filter(family -> family.bansAlone() == bansAlone)
        .mapToInt(this.properties::weight)
        .sum();
  }

  private void ban(
      final Cache<String, Long> banned,
      final String client,
      final String remoteAddr,
      final Set<SignalFamily> signals,
      final long now) {

    if (banned.asMap().putIfAbsent(client, now) != null) {
      return;
    }

    log.warn(
        "Client {} (network {}) sent suspicious requests {} reaching {} strikes ({})",
        remoteAddr,
        client,
        signals,
        this.properties.maxStrikes(),
        this.mode() == AuthThrottleMode.ENFORCE
            ? "banning it for " + this.properties.banSeconds() + "s"
            : "would be banned if repsy.security.probe-throttle.mode were enforce");
    this.count(BANS_METRIC);
    this.announce(client);
  }

  private void announce(final String client) {

    final var listener = this.banListener;

    if (listener == null) {
      return;
    }

    try {
      listener.banned(BanKind.PROBE, client, this.properties.banSeconds());
    } catch (final RuntimeException e) {
      log.warn("The ban of client {} could not be shared with the other instances", client, e);
    }
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

  private Window next(
      final @Nullable Window current, final long now, final int added, final int soft) {

    if (current == null || now - current.startNanos() >= this.windowNanos) {
      return new Window(now, added, soft);
    }

    // The sum stays far below int overflow: it stops growing at the ban, but a long ban window
    // could still be fed, so cap it.
    return new Window(
        current.startNanos(),
        (int) Math.min((long) current.strikes() + added, Integer.MAX_VALUE),
        (int) Math.min((long) current.softStrikes() + soft, Integer.MAX_VALUE));
  }

  private void count(final String metric) {

    final var registry = this.meterRegistry.get();

    if (registry != null) {
      registry.counter(metric).increment();
    }
  }

  private void count(final String metric, final SignalFamily family) {

    final var registry = this.meterRegistry.get();

    if (registry != null) {
      registry.counter(metric, "family", family.key()).increment();
    }
  }

  /** {@code softStrikes} are of families that cannot ban alone, see {@link SignalFamily}. */
  private record Window(long startNanos, int strikes, int softStrikes) {

    long total() {

      return (long) this.strikes + this.softStrikes;
    }

    /** Whether the window is full, and at least one strike is of a family that can ban. */
    boolean bans(final int maxStrikes) {

      return this.strikes > 0 && this.total() >= maxStrikes;
    }
  }

  /** An address range, {@code 10.0.0.0/8}; a bare address is a range of one. */
  private static final class Cidr {

    private static final int BITS_PER_BYTE = 8;

    private final byte[] network;
    private final int prefixBits;

    private Cidr(final byte[] network, final int prefixBits) {

      this.network = network.clone();
      this.prefixBits = prefixBits;
    }

    static Cidr parse(final String text) {

      final var slash = text.indexOf('/');
      final var host = slash < 0 ? text : text.substring(0, slash);
      final byte[] bytes;

      try {
        bytes = InetAddresses.forString(host.trim()).getAddress();
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "repsy.security.probe-throttle.allowlist: not an address or CIDR range: " + text, e);
      }

      final var bits =
          slash < 0
              ? bytes.length * Cidr.BITS_PER_BYTE
              : Integer.parseInt(text.substring(slash + 1).trim());

      if (bits < 0 || bits > bytes.length * Cidr.BITS_PER_BYTE) {
        throw new IllegalArgumentException(
            "repsy.security.probe-throttle.allowlist: bad prefix length: " + text);
      }

      return new Cidr(bytes, bits);
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
}
