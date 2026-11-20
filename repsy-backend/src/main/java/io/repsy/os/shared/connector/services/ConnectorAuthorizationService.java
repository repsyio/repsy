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
package io.repsy.os.shared.connector.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.ConnectorAuthorizationForm;
import io.repsy.os.generated.model.ConnectorAuthorizationInfo;
import io.repsy.os.generated.model.ConnectorTokenExchangeForm;
import io.repsy.os.shared.connector.configs.ConnectorProperties;
import io.repsy.os.shared.connector.entities.ConnectorAuthorizationCode;
import io.repsy.os.shared.connector.repositories.ConnectorAuthorizationCodeRepository;
import io.repsy.os.shared.connector.utils.PkceUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consent flow of connected applications (RPS-2660), such as the remote MCP gateway of Repsy
 * Cloud: a signed-in user approves on the panel, which issues a one-time code bound to the
 * registered redirect and the PKCE challenge of the application; the application exchanges it,
 * server to server, for a {@code CONNECTOR} token of the personal access token table. The token
 * itself never passes through the browser.
 *
 * <p>Everything here answers 404 {@code itemNotFound} while the flow is turned off ({@link
 * ConnectorProperties#enabled()}, the default), as a path nothing maps does.
 */
@Service
@Transactional(readOnly = true)
public class ConnectorAuthorizationService {

  /** How long a code can be exchanged. The application exchanges it as soon as it gets it. */
  public static final Duration CODE_LIFETIME = Duration.ofSeconds(60);

  /** The scopes a connected application may be granted: the repo scopes, never a deprecated one. */
  static final Set<TokenScope> GRANTABLE_SCOPES =
      EnumSet.of(TokenScope.REPO_READ, TokenScope.REPO_WRITE, TokenScope.REPO_MANAGE);

  /** The longest application name a token keeps, the length of the {@code name} column. */
  static final int MAX_CLIENT_NAME_LENGTH = 150;

  private static final int CODE_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConnectorProperties properties;
  private final ConnectorAuthorizationCodeRepository codeRepository;
  private final UserRepository userRepository;
  private final PersonalAccessTokenService tokenService;
  private final Clock clock;

  @Autowired
  public ConnectorAuthorizationService(
      final ConnectorProperties properties,
      final ConnectorAuthorizationCodeRepository codeRepository,
      final UserRepository userRepository,
      final PersonalAccessTokenService tokenService) {
    this(properties, codeRepository, userRepository, tokenService, Clock.systemUTC());
  }

  /** A service that reads {@code clock}, so a test can let a code expire without waiting. */
  ConnectorAuthorizationService(
      final ConnectorProperties properties,
      final ConnectorAuthorizationCodeRepository codeRepository,
      final UserRepository userRepository,
      final PersonalAccessTokenService tokenService,
      final Clock clock) {
    this.properties = properties;
    this.codeRepository = codeRepository;
    this.userRepository = userRepository;
    this.tokenService = tokenService;
    this.clock = clock;
  }

  /**
   * Refuses every request while connected applications are turned off, with the answer of a path
   * nothing maps.
   *
   * @throws ItemNotFoundException ({@code itemNotFound}) if the flow is turned off
   */
  public void requireEnabled() {
    if (!this.properties.enabled()) {
      throw new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND);
    }
  }

  /**
   * Records the consent of {@code userId} and returns its one-time code, which is shown here only:
   * only its hash is stored. Codes that have expired, of any user, are deleted on the way.
   *
   * @throws BadRequestException ({@code connectorRedirectUriNotRegistered}) if the redirect is not
   *     registered, ({@code validationError}) if a scope is not grantable, the name is blank or the
   *     PKCE challenge is not an S256 one
   */
  @Transactional
  public ConnectorAuthorizationInfo authorize(
      final UUID userId, final ConnectorAuthorizationForm form) {

    this.requireEnabled();

    if (!this.properties.isRegistered(form.getRedirectUri())) {
      throw new BadRequestException(ErrorConstants.CONNECTOR_REDIRECT_URI_NOT_REGISTERED);
    }

    final var scopes = grantableScopes(form.getScopes());
    final var name = clientName(form.getClientName());

    if (!PkceUtils.METHOD_S256.equals(form.getCodeChallengeMethod())
        || !PkceUtils.isChallenge(form.getCodeChallenge())) {
      throw new BadRequestException(ProtocolErrorCodes.VALIDATION_ERROR);
    }

    final var now = this.now();

    this.codeRepository.deleteExpired(now);

    final var code = newCode();
    final var row = new ConnectorAuthorizationCode();

    row.setUser(this.userRepository.getReferenceById(userId));
    row.setCodeHash(TokenHash.hash(code));
    row.setClientName(name);
    row.setScopes(TokenScope.withImplicit(scopes));
    row.setRedirectUri(form.getRedirectUri());
    row.setCodeChallenge(form.getCodeChallenge());
    row.setExpiresAt(now.plus(CODE_LIFETIME));
    this.codeRepository.saveAndFlush(row);

    return ConnectorAuthorizationInfo.builder()
        .id(row.getId())
        .code(code)
        .expiresAt(row.getExpiresAt())
        .build();
  }

  /**
   * Exchanges a one-time code for the connector token of its consent. The code is used up by the
   * first exchange that finds it, whatever the outcome: the deletion is kept when the answer is a
   * refusal, so a wrong verifier cannot be retried.
   *
   * @throws BadRequestException ({@code invalidConnectorCode}) for an unknown, used or expired
   *     code, a wrong verifier or another redirect, one answer for all of them; ({@code
   *     connectorTokenLimitReached}) if the user holds the most connector tokens already
   */
  @Transactional(noRollbackFor = BadRequestException.class)
  public AccessTokenCreated exchange(final ConnectorTokenExchangeForm form) {

    this.requireEnabled();

    final var row =
        this.codeRepository
            .findByCodeHash(TokenHash.hash(form.getCode()))
            .orElseThrow(ConnectorAuthorizationService::invalidCode);

    this.codeRepository.delete(row);
    this.codeRepository.flush();

    final var valid =
        row.getExpiresAt().isAfter(this.now())
            && row.getRedirectUri().equals(form.getRedirectUri())
            && PkceUtils.verify(form.getCodeVerifier(), row.getCodeChallenge());

    if (!valid) {
      throw invalidCode();
    }

    return this.tokenService.createConnectorToken(
        row.getUser().getId(), row.getClientName(), row.getScopes());
  }

  private static BadRequestException invalidCode() {
    return new BadRequestException(ErrorConstants.INVALID_CONNECTOR_CODE);
  }

  private static Set<TokenScope> grantableScopes(final Set<TokenScope> requested) {
    if (requested.isEmpty()
        || requested.stream().anyMatch(Objects::isNull)
        || !GRANTABLE_SCOPES.containsAll(requested)) {
      throw new BadRequestException(ProtocolErrorCodes.VALIDATION_ERROR);
    }

    return EnumSet.copyOf(requested);
  }

  /**
   * The name the token keeps: the one the application gave, with control characters dropped,
   * trimmed and cut to {@link #MAX_CLIENT_NAME_LENGTH}.
   */
  static String clientName(final String requested) {
    final var cleaned = requested.replaceAll("\\p{Cntrl}", "").strip();

    if (cleaned.isEmpty()) {
      throw new BadRequestException(ProtocolErrorCodes.VALIDATION_ERROR);
    }

    return cleaned.length() > MAX_CLIENT_NAME_LENGTH
        ? cleaned.substring(0, MAX_CLIENT_NAME_LENGTH)
        : cleaned;
  }

  private static String newCode() {
    final var bytes = new byte[CODE_BYTES];

    RANDOM.nextBytes(bytes);

    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The time of the request, truncated to what the timestamp columns keep. */
  private Instant now() {
    return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
