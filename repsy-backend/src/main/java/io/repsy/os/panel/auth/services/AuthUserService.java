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
package io.repsy.os.panel.auth.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.events.UserLoginEvent;
import io.repsy.os.generated.model.LoginForm;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
import io.repsy.os.shared.auth.services.LoginInfoFactory;
import io.repsy.os.shared.auth.services.RefreshTokenService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.auth.AuthFailureThrottle;
import io.repsy.protocols.shared.auth.PasswordHasher;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthUserService {

  private final UserTxService userTxService;
  private final LoginInfoFactory loginInfoFactory;
  private final RefreshTokenService refreshTokenService;
  private final ApplicationEventPublisher eventPublisher;
  private final AuthFailureThrottle authFailureThrottle;
  private final TransactionOperations transactionOperations;

  // RPS-2176: no transaction around the whole login. The hash upgrade runs in a transaction of its
  // own (REQUIRES_NEW, a protocol facade may call it from a read-only one), which needs a second
  // pooled connection while the login's transaction held the first: as many concurrent logins as
  // the pool has connections waited on each other until Hikari's connection timeout. The password
  // is checked and the hash upgraded before any transaction is open; the user row lock and the
  // refresh token write, which must be one transaction (RPS-1152), are the one below.
  @Transactional(propagation = Propagation.SUPPORTS)
  public LoginInfo login(final LoginForm form) {

    // Before the user lookup: a blocked client learns nothing about a username, whichever it sends
    // (RPS-906). A refused login costs no BCrypt (RPS-1092).
    this.authFailureThrottle.checkAllowed();

    final UserInfo user;
    try {
      user = this.userTxService.getUserByUsername(form.getUsername());
    } catch (final ItemNotFoundException exception) {
      // Perform the same hash work for unknown usernames to avoid leaking account existence.
      PasswordHasher.verifyDummy(form.getPassword());
      this.authFailureThrottle.recordFailure();
      throw new UnAuthorizedException(ErrorConstants.INVALID_CREDENTIALS);
    }

    if (!PasswordHasher.matches(form.getPassword(), user.getHash(), user.getSalt())) {
      this.authFailureThrottle.recordFailure();
      throw new UnAuthorizedException(ErrorConstants.INVALID_CREDENTIALS);
    }

    // Hashes from an older algorithm or work factor are replaced now that the password is known.
    if (PasswordHasher.needsUpgrade(user.getHash(), form.getPassword())) {
      this.userTxService.upgradePasswordHash(user, form.getPassword());
    }

    // Publish login event for lastLoginAt update
    this.eventPublisher.publishEvent(new UserLoginEvent(user.getUsername()));

    return Objects.requireNonNull(
        this.transactionOperations.execute(
            status -> {
              // Locks the user row so a deletion racing this request either waits for the refresh
              // token to be written, or has already committed and is caught here, instead of a
              // foreign-key violation surfacing from the insert (RPS-1152).
              if (!this.userTxService.lockUserExists(user.getId())) {
                throw new UnAuthorizedException(ErrorConstants.INVALID_CREDENTIALS);
              }

              return this.loginInfoFactory.create(
                  user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
            }));
  }

  // consume() joins this method's transaction (both @Transactional, REQUIRED), so it is this
  // method's own rollback rule, not consume()'s, that decides whether a replay's family-wide
  // revocation survives: consume() is never the outermost transaction boundary here, so its own
  // noRollbackFor only stops it from marking the shared transaction rollback-only, while this
  // method's default rollback-on-RuntimeException would otherwise still roll the whole thing back
  // once the UnAuthorizedException it rethrows reaches this method's transactional advice
  // (RPS-1682).
  @Transactional(noRollbackFor = UnAuthorizedException.class)
  public LoginInfo refreshToken(final RefreshTokenClaims claims) {

    this.refreshTokenService.consume(claims);

    final var user = this.userTxService.getAuthenticatedUserById(claims.userId());

    // A password or username change bumps the version, which revokes the older refresh tokens.
    if (claims.tokenVersion() != user.getTokenVersion()) {
      throw new UnAuthorizedException(ErrorConstants.REFRESH_TOKEN_EXPIRED);
    }

    // The tokens' own expiry is capped at the session end; this guards it independently.
    if (!Instant.now().isBefore(claims.sessionStart().plus(AuthUtils.TIMEOUT_SESSION))) {
      throw new UnAuthorizedException(ErrorConstants.REFRESH_TOKEN_EXPIRED);
    }

    // Locks the user row so a deletion racing this request either waits for the refresh token to
    // be written, or has already committed and is caught here, instead of a foreign-key violation
    // surfacing from the insert (RPS-1152).
    if (!this.userTxService.lockUserExists(user.getId())) {
      throw new UnAuthorizedException(ErrorConstants.REFRESH_TOKEN_EXPIRED);
    }

    return this.loginInfoFactory.create(user, claims.sessionStart(), claims.familyId());
  }
}
