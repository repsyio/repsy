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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.dtos.UserInfo;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Remembers successful password checks, so a client that sends its HTTP Basic credentials on every
 * request pays for one BCrypt verification instead of one per request (RPS-1025).
 *
 * <p>What is remembered is that <em>this password matched this stored hash</em>, never a user
 * session:
 *
 * <ul>
 *   <li>The key is an HMAC of the username, the password and the stored hash, under a random key
 *       made for this process. No password sits in memory, and a client cannot craft a key. Because
 *       the stored hash is part of it, a changed password, a re-hash and a new algorithm all miss
 *       the cache at once, with no invalidation to forget. The caller still reads the user from the
 *       database on every request, so a deleted user or a changed role takes effect immediately
 *       too.
 *   <li>Only a successful check is stored. A wrong password, an unknown username and a missing user
 *       never reach the cache, so those requests cost what they did before (RPS-906), and a client
 *       without a valid password cannot fill it.
 *   <li>Entries expire after a fixed time and the cache holds a bounded number of them.
 * </ul>
 */
@Component
public class VerifiedPasswordCache {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int KEY_BYTES = 32;

  private final @Nullable Cache<String, Boolean> verified;
  private final @NonNull SecretKeySpec macKey;

  @Autowired
  public VerifiedPasswordCache(final @NonNull BasicAuthCacheProperties properties) {

    this(properties, Ticker.systemTicker());
  }

  @VisibleForTesting
  VerifiedPasswordCache(
      final @NonNull BasicAuthCacheProperties properties, final @NonNull Ticker ticker) {

    final var keyBytes = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(keyBytes);
    this.macKey = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);

    this.verified =
        properties.enabled()
            ? CacheBuilder.newBuilder()
                .maximumSize(properties.maxEntries())
                .expireAfterWrite(Duration.ofSeconds(properties.ttlSeconds()))
                .ticker(ticker)
                .recordStats()
                .build()
            : null;
  }

  /**
   * Checks a password against the stored hash of {@code user}, skipping the hash check when the
   * same password already matched the same stored hash a moment ago.
   *
   * @see PasswordHasher#matches
   */
  public boolean matches(final @NonNull UserInfo user, final @NonNull String password) {

    if (this.verified == null || user.getHash() == null) {
      return PasswordHasher.matches(password, user.getHash());
    }

    final var key = this.keyOf(user, password);

    if (this.verified.getIfPresent(key) != null) {
      return true;
    }

    final var matches = PasswordHasher.matches(password, user.getHash());

    if (matches) {
      this.verified.put(key, Boolean.TRUE);
    }

    return matches;
  }

  /**
   * Tells whether this password already matched the stored hash of {@code user} a moment ago, which
   * costs a lookup and never a hash check.
   */
  public boolean isRemembered(final @NonNull UserInfo user, final @NonNull String password) {

    return this.verified != null
        && user.getHash() != null
        && this.verified.getIfPresent(this.keyOf(user, password)) != null;
  }

  @VisibleForTesting
  long hitCount() {

    return this.verified == null ? 0 : this.verified.stats().hitCount();
  }

  private @NonNull String keyOf(final @NonNull UserInfo user, final @NonNull String password) {

    final var mac = this.newMac();

    update(mac, user.getUsername());
    update(mac, password);
    update(mac, user.getHash());

    return Base64.getEncoder().encodeToString(mac.doFinal());
  }

  /** Each part is length-prefixed, so ("ab", "c") and ("a", "bc") make different keys. */
  private static void update(final @NonNull Mac mac, final @Nullable String part) {

    if (part == null) {
      mac.update((byte) 0);
      return;
    }

    final var bytes = part.getBytes(UTF_8);

    mac.update((byte) 1);
    mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    mac.update(bytes);
  }

  private @NonNull Mac newMac() {

    try {
      final var mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(this.macKey);
      return mac;
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(HMAC_ALGORITHM + " is not available", e);
    }
  }
}
