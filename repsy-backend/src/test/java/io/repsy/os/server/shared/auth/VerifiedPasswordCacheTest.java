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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Ticker;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RPS-1025: a remembered password check must never outlive what it was made against. */
@DisplayName("VerifiedPasswordCache")
class VerifiedPasswordCacheTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";
  private static final String SALT = "salt";
  private static final long TTL_SECONDS = 300;

  /** BCrypt is slow on purpose, so the hashes are made once for the whole class. */
  private static final String BCRYPT_HASH = PasswordHasher.hash(PASSWORD);

  private static final String OTHER_BCRYPT_HASH = PasswordHasher.hash("another-s3cret");

  private final AtomicLong nanos = new AtomicLong();

  private final Ticker ticker =
      new Ticker() {
        @Override
        public long read() {
          return VerifiedPasswordCacheTest.this.nanos.get();
        }
      };

  private final VerifiedPasswordCache cache =
      new VerifiedPasswordCache(new BasicAuthCacheProperties(true, TTL_SECONDS, 100), this.ticker);

  private static UserInfo user(final String username, final String hash, final String salt) {
    return UserInfo.builder()
        .id(UUID.randomUUID())
        .username(username)
        .hash(hash)
        .salt(salt)
        .role(UserRole.USER)
        .build();
  }

  @Test
  @DisplayName("remembers a successful check, so the same credentials skip the hash check")
  void remembersSuccess() {
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);

    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();
    assertThat(this.cache.hitCount()).isZero();

    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();
    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();
    assertThat(this.cache.hitCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("never remembers a wrong password, so it is checked in full every time")
  void doesNotRememberFailure() {
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);

    assertThat(this.cache.matches(alice, "wrong")).isFalse();
    assertThat(this.cache.matches(alice, "wrong")).isFalse();

    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("a failed check does not make the right password fail, and vice versa")
  void failureDoesNotAffectSuccess() {
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);

    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();
    assertThat(this.cache.matches(alice, "wrong")).isFalse();
    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();

    assertThat(this.cache.hitCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a changed password no longer matches, although the old one was remembered")
  void passwordChangeInvalidates() {
    assertThat(this.cache.matches(user(USERNAME, BCRYPT_HASH, SALT), PASSWORD)).isTrue();

    final var afterChange = user(USERNAME, OTHER_BCRYPT_HASH, SALT);

    assertThat(this.cache.matches(afterChange, PASSWORD)).isFalse();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("a changed salt of a legacy hash no longer matches")
  void saltChangeInvalidates() {
    final var legacyHash = DigestUtils.sha256Hex(PASSWORD + SALT);

    assertThat(this.cache.matches(user(USERNAME, legacyHash, SALT), PASSWORD)).isTrue();

    assertThat(this.cache.matches(user(USERNAME, legacyHash, "other-salt"), PASSWORD)).isFalse();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("a null salt and an empty salt are not the same credential")
  void nullSaltIsNotEmptySalt() {
    final var hashWithNullSalt = DigestUtils.sha256Hex(PASSWORD + null);

    assertThat(this.cache.matches(user(USERNAME, hashWithNullSalt, null), PASSWORD)).isTrue();

    assertThat(this.cache.matches(user(USERNAME, hashWithNullSalt, ""), PASSWORD)).isFalse();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("another user with the same password and hash does not share the entry")
  void entriesAreNotSharedAcrossUsernames() {
    assertThat(this.cache.matches(user(USERNAME, BCRYPT_HASH, SALT), PASSWORD)).isTrue();

    assertThat(this.cache.matches(user("bob", BCRYPT_HASH, SALT), PASSWORD)).isTrue();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("the parts of a key cannot be shifted into one another")
  void partsAreDelimited() {
    // ("ab", "c") and ("a", "bc") concatenate to the same text but are different credentials. Only
    // the first one is right for the hash, so a shared key would let the second one in.
    final var hash = DigestUtils.sha256Hex("c" + SALT);

    assertThat(this.cache.matches(user("ab", hash, SALT), "c")).isTrue();

    assertThat(this.cache.matches(user("a", hash, SALT), "bc")).isFalse();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("a user without a password hash never matches and is never remembered")
  void nullHash() {
    final var noPassword = user(USERNAME, null, SALT);

    assertThat(this.cache.matches(noPassword, PASSWORD)).isFalse();
    assertThat(this.cache.matches(noPassword, PASSWORD)).isFalse();
    assertThat(this.cache.hitCount()).isZero();
  }

  @Test
  @DisplayName("forgets a check once the time to live has passed")
  void expires() {
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);

    this.cache.matches(alice, PASSWORD);

    this.nanos.addAndGet(Duration.ofSeconds(TTL_SECONDS - 1).toNanos());
    this.cache.matches(alice, PASSWORD);
    assertThat(this.cache.hitCount()).isEqualTo(1);

    this.nanos.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(this.cache.matches(alice, PASSWORD)).isTrue();
    assertThat(this.cache.hitCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("keeps a bounded number of checks")
  void isBounded() {
    final var bounded =
        new VerifiedPasswordCache(new BasicAuthCacheProperties(true, TTL_SECONDS, 1), this.ticker);
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);
    final var bob = user("bob", BCRYPT_HASH, SALT);

    bounded.matches(alice, PASSWORD);
    bounded.matches(bob, PASSWORD);

    assertThat(bounded.matches(alice, PASSWORD)).isTrue();
    assertThat(bounded.hitCount()).isZero();
  }

  @Test
  @DisplayName("checks in full every time when disabled")
  void disabled() {
    final var disabled = new VerifiedPasswordCache(BasicAuthCacheProperties.disabled());
    final var alice = user(USERNAME, BCRYPT_HASH, SALT);

    assertThat(disabled.matches(alice, PASSWORD)).isTrue();
    assertThat(disabled.matches(alice, PASSWORD)).isTrue();
    assertThat(disabled.matches(alice, "wrong")).isFalse();
    assertThat(disabled.hitCount()).isZero();
  }

  @Test
  @DisplayName("refuses settings that could not hold an entry")
  void rejectsNonPositiveSettings() {
    assertThatThrownBy(() -> new BasicAuthCacheProperties(true, 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BasicAuthCacheProperties(true, 10, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new BasicAuthCacheProperties(false, 0, 0).enabled()).isFalse();
  }
}
