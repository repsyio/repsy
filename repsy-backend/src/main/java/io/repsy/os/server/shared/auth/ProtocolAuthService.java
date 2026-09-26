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

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromAuthHeader;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.generated.model.RepoPermissionInfo;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.dtos.PermissionInfo;
import io.repsy.os.shared.auth.services.RevokedProtocolTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProtocolAuthService {

  private static final @NonNull String ACCESS_DENIED = "accessDenied";

  protected final @NonNull UserTxService userTxService;
  protected final @NonNull JwtUtils jwtUtils;
  protected final @NonNull DeployTokenService deployTokenService;
  protected final @NonNull VerifiedPasswordCache verifiedPasswordCache;
  protected final @NonNull AuthFailureThrottle authFailureThrottle;

  private @Nullable RevokedProtocolTokenService revokedTokens;

  /**
   * Setter-injected so that the protocol auth components, which all pass the five arguments above
   * to {@code super}, need no change. It is always present in the application; it is absent only in
   * a unit test that builds a component by hand, which then has no revoked tokens.
   */
  @Autowired(required = false)
  public void setRevokedTokens(final @Nullable RevokedProtocolTokenService revokedTokens) {
    this.revokedTokens = revokedTokens;
  }

  /**
   * The credential a protocol request carries, which is its {@code Authorization} header and
   * nothing else. A {@code ?token=} query parameter is not read: a credential in a URL ends up in
   * browser history, proxy and access logs and {@code Referer} headers (RPS-1044). No supported
   * package manager sends one; a browser navigation that cannot set the header uses the single-path
   * download token instead (RPS-980).
   */
  public @Nullable String emulateAuthHeader(final @NonNull HttpServletRequest request) {

    return request.getHeader(HttpHeaders.AUTHORIZATION);
  }

  /**
   * Authorizes a bearer JWT or deploy token. Protocol endpoints take protocol tokens only.
   *
   * <p>A JWT minted from a deploy token ({@link AuthenticationType#DEPLOY_TOKEN}) is authorized as
   * that deploy token: bound to its repo, read-only and expiry checked. Its {@code username} claim
   * is whatever the client typed into the Basic credentials, so it never identifies a user
   * (RPS-979).
   *
   * <p>A Bearer value that is neither a live deploy token nor a verifiable protocol JWT answers
   * {@code unAuthorized} and counts against {@link AuthFailureThrottle}, like a wrong Basic
   * password (RPS-1209). An expired protocol JWT is answered {@code sessionExpired} and not
   * counted.
   */
  public void handleBearerAuth(
      final @NonNull String authHeader,
      final @NonNull UUID repoId,
      final @NonNull Permission permission) {

    final var bearerToken = authHeader.substring(AUTH_BEARER.length());

    if (this.acceptsRawDeployTokenBearer()
        && this.tryAuthorizeWithDeployToken(repoId, bearerToken, permission)) {
      return;
    }

    final var authenticationType = this.verifiedAuthenticationType(authHeader);

    this.rejectRevokedToken(bearerToken);

    if (authenticationType == AuthenticationType.DEPLOY_TOKEN) {
      this.authorizeTokenRequestTokenId(
          repoId, this.jwtUtils.extractUserId(authHeader, TokenRealm.PROTOCOL), permission);
      return;
    }

    // A scanner token is repo-scoped and has no user; only Docker knows how to authorize one, so
    // every other protocol keeps refusing it through the hook's default.
    if (authenticationType == AuthenticationType.DOCKER_SCAN) {
      this.authorizeScannerBearer(authHeader, repoId, permission);
      return;
    }

    // An anonymous token has no user either, so its username claim must not be looked up
    // (RPS-986).
    if (authenticationType == AuthenticationType.ANONYMOUS) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authorizeJWTRequest(authHeader, permission);
  }

  /**
   * Refuses a protocol JWT that was revoked, for example by {@code npm logout} (RPS-1361). It is
   * called after the signature was verified, so a made-up Bearer value never costs a lookup. A
   * revoked token is a credential Repsy recognizes, so like a revoked deploy token it is refused
   * with {@code unAuthorized} and does not count against {@link AuthFailureThrottle}.
   *
   * @param token The JWT without its {@code Bearer} prefix
   */
  protected void rejectRevokedToken(final @NonNull String token) {
    final var registry = this.revokedTokens;

    if (registry != null && registry.isRevoked(token)) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  /**
   * Whether a raw deploy-token secret may be presented as the Bearer value itself, as {@link
   * #tryAuthorizeWithDeployToken} checks. True for every protocol except Docker, whose clients
   * exchange Basic credentials for a JWT at {@code /v2/token} first: a raw deploy-token secret
   * handed to {@code /v2} as a Bearer value must be refused, not silently accepted.
   */
  protected boolean acceptsRawDeployTokenBearer() {
    return true;
  }

  /**
   * Authorizes a {@link AuthenticationType#DOCKER_SCAN} bearer token. Only Docker issues this type
   * (a repo-scoped, user-less token minted for its vulnerability scanner), so every other protocol
   * keeps this default of refusing it.
   */
  protected void authorizeScannerBearer(
      final @NonNull String authHeader,
      final @NonNull UUID repoId,
      final @NonNull Permission permission) {
    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  /**
   * Verifies the bearer JWT and returns its authentication type. A Bearer value that is no live
   * deploy token and no protocol JWT is a wrong credential: it answers {@code unAuthorized} like a
   * wrong password and counts as one failure against the client (RPS-1209), whether the JWT has a
   * bad signature, the wrong realm or is no JWT at all.
   *
   * <p>A validly signed JWT that has merely expired is a credential Repsy recognizes, not a guess:
   * Docker and Helm clients hold short-lived tokens, and a long push over a shared address, with
   * its parallel layer uploads, would otherwise spend the budget of every client behind it. It is
   * answered {@code sessionExpired} as before and is not counted.
   *
   * <p>{@link JwtUtils#extractAuthenticationType} rejects a claim it does not recognize with {@link
   * BadRequestException}. That is right for a request body the client controls, but an unrecognized
   * {@code authentication_type} claim in a bearer token is a credential problem, not a bad request,
   * so every protocol answers it the same way an invalid signature would: 401 (RPS-1171).
   *
   * <p>The check comes after the verification, so a verified token is never refused for the count,
   * like a remembered password (RPS-1092). What Repsy recognized but refuses (a revoked or
   * read-only deploy token, no ADMIN for MANAGE) is decided later and does not count either.
   */
  private @NonNull AuthenticationType verifiedAuthenticationType(final @NonNull String authHeader) {
    try {
      return this.jwtUtils.extractAuthenticationType(authHeader, TokenRealm.PROTOCOL);
    } catch (final UnAuthorizedException ex) {
      if (ErrorConstants.SESSION_EXPIRED.equals(ex.getMessage())) {
        throw ex;
      }
      throw this.countedUnAuthorized();
    } catch (final BadRequestException _) {
      throw this.countedUnAuthorized();
    }
  }

  /** Counts one failed credential against the client and returns the answer to give for it. */
  private @NonNull UnAuthorizedException countedUnAuthorized() {
    this.authFailureThrottle.checkAllowed();
    this.authFailureThrottle.recordFailure();
    return new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  /**
   * Authorizes a read with a download token, which the web UI hands to a browser navigation because
   * that cannot set an {@code Authorization} header. The token opens one path of one repo, for
   * reads only; the caller was authorized when it asked for the token.
   */
  public void handleDownloadToken(
      final @NonNull String token,
      final @NonNull UUID repoId,
      final @NonNull String path,
      final @NonNull Permission permission) {

    if (permission != Permission.READ) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.jwtUtils.verifyDownloadToken(token, repoId, path);
  }

  public void handleBasicAuth(
      final @NonNull String authHeader,
      final @NonNull Permission permission,
      final @NonNull UUID repoId) {

    final var basicToken = removeBasicPrefix(authHeader);
    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (this.tryAuthorizeWithDeployToken(repoId, credentials.getPassword(), permission)) {
      return;
    }

    this.handleUsernamePasswordAuthentication(credentials, permission);
  }

  /**
   * Authorizes an already authenticated user on a protocol (wire) route. A user without the needed
   * permission is answered {@code unAuthorized} (401), which is the answer package managers expect
   * and act on. The web UI API uses {@link #authorizePanelUser} instead.
   */
  public @NonNull PermissionInfo authorizeUser(
      final @Nullable UserInfo userInfo, final @NonNull Permission permission) {

    if (userInfo == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.checkPermission(userInfo, permission);

    final var isAdmin = userInfo.getRole() == UserRole.ADMIN;

    return PermissionInfo.builder().canRead(true).canWrite(true).canManage(isAdmin).build();
  }

  /**
   * Authorizes an already authenticated user on a web UI API route ({@code @RepoOperation}). It
   * differs from {@link #authorizeUser} in one thing: a signed-in user who lacks the role for the
   * operation ({@code MANAGE} needs ADMIN) is answered {@code accessDenied} (403, as {@code
   * PanelAuthHelper#requireAdmin} does), not {@code unAuthorized} (401). Only a missing or invalid
   * credential is a 401 there, so the SPA can tell a lost session from a refused action (RPS-1284).
   * A {@code null} user is still a 401.
   */
  public @NonNull PermissionInfo authorizePanelUser(
      final @Nullable UserInfo userInfo, final @NonNull Permission permission) {

    if (userInfo != null
        && permission == Permission.MANAGE
        && userInfo.getRole() != UserRole.ADMIN) {
      throw new AccessNotAllowedException(ACCESS_DENIED);
    }

    return this.authorizeUser(userInfo, permission);
  }

  /** Authorizes a web UI API request to one repo, see {@link #authorizePanelUser}. */
  public @NonNull RepoPermissionInfo authorizeUserRequest(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String authHeader,
      final @NonNull Permission permission) {

    if (authHeader != null) {
      return this.authorizeRepoUser(repoInfo, authHeader, permission);
    }

    // Auth header is null
    if (this.isPublicReadAccess(repoInfo, permission)) {
      return RepoPermissionInfo.builder()
          .repoName(repoInfo.getName())
          .description(repoInfo.getDescription())
          ._private(false)
          .canRead(true)
          .canWrite(false)
          .canManage(false)
          .build();
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  /** Authenticates a web UI API request, so a bearer token has to be a panel access token. */
  public @NonNull UserInfo authenticateUser(final @Nullable String authHeader) {

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return switch (authHeader) {
      case final String header when isBasicToken(header) -> this.authenticateWithBasic(header);
      case final String header when isBearerToken(header) -> this.authenticateWithBearer(header);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    };
  }

  protected boolean isPublicReadAccess(
      final @NonNull RepoInfo repoInfo, final @NonNull Permission permission) {

    return !repoInfo.isPrivateRepo() && Permission.READ.equals(permission);
  }

  protected boolean tryAuthorizeWithDeployToken(
      final @NonNull UUID repoId,
      final @NonNull String token,
      final @NonNull Permission permission) {

    final var deployTokenInfoOpt = this.deployTokenService.findByRepoIdAndToken(repoId, token);

    if (deployTokenInfoOpt.isEmpty()) {
      return false;
    }

    this.authorizeDeployTokenRequest(deployTokenInfoOpt.get(), permission);

    return true;
  }

  public void authorizeTokenRequestTokenId(
      final @NonNull UUID repoId,
      final @NonNull UUID tokenId,
      final @NonNull Permission permission) {

    // A deploy token never manages, whatever its read-only flag says (RPS-1424).
    if (permission == Permission.MANAGE) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var deployTokenInfo = this.deployTokenService.findByRepoIdAndTokenId(repoId, tokenId);

    if (deployTokenInfo.isEmpty()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var deployToken = deployTokenInfo.get();

    if (deployToken.isExpired()) {
      throw new UnAuthorizedException("deployTokenExpired");
    }

    if (this.isWritePermissionRequired(permission) && deployToken.isReadOnly()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.deployTokenService.updateLastUsedTime(deployToken.getId());
  }

  protected boolean isWritePermissionRequired(final @NonNull Permission permission) {

    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }

  private void authorizeDeployTokenRequest(
      final @NonNull DeployTokenInfo deployTokenInfo, final @NonNull Permission permission) {

    if (deployTokenInfo.isExpired()) {
      throw new UnAuthorizedException("deployTokenExpired");
    }

    this.authorizeDeployToken(deployTokenInfo, permission);
    this.deployTokenService.updateLastUsedTime(deployTokenInfo.getId());
  }

  /**
   * A deploy token reads and writes, and never manages: a request that needs {@link
   * Permission#MANAGE} (removing stored files, for example {@code npm unpublish}, RPS-1424) is
   * refused for it, read-only or not, so a CI's credential cannot delete what it published. The
   * panel offers those operations to admins only.
   */
  private void authorizeDeployToken(
      final @NonNull DeployTokenInfo deployTokenInfo, final @NonNull Permission permission) {

    if (permission == Permission.MANAGE) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (this.isWritePermissionRequired(permission) && deployTokenInfo.isReadOnly()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void authorizeJWTRequest(
      final @NonNull String authHeader, final @NonNull Permission permission) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader, TokenRealm.PROTOCOL);
    final var userInfo = this.userTxService.getAuthenticatedUserByUsername(username);

    this.authorizeUser(userInfo, permission);
  }

  protected void handleUsernamePasswordAuthentication(
      final @NonNull Credentials credentials, final @NonNull Permission permission) {

    this.authorizeUser(this.authenticateWithPassword(credentials), permission);
  }

  /**
   * Resolves the user behind a username/password pair. An unknown username, a missing username and
   * a wrong password all fail with the same {@code unAuthorized} error, so the response does not
   * reveal which usernames exist.
   */
  protected @NonNull UserInfo authenticateWithPassword(final @NonNull Credentials credentials) {

    final var username = credentials.getUsername();
    final var password = credentials.getPassword();

    if (username == null || password == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var userInfo = this.userTxService.getUserByUsernameOptional(username).orElse(null);

    // A password the cache remembers costs no hash check, so it is let through even for a client
    // that is blocked: a CI behind a shared address keeps working while a neighbour that sends
    // wrong passwords is refused. It is not counted, since it did not fail.
    if (userInfo == null || !this.isRemembered(userInfo, password)) {
      this.checkPassword(userInfo, password);
    }

    // Hashes from an older algorithm or work factor are replaced now that the password is known.
    if (PasswordHasher.needsUpgrade(userInfo.getHash())) {
      this.userTxService.upgradePasswordHash(userInfo, password);
    }

    return userInfo;
  }

  /**
   * The password check that costs a BCrypt verification. The client is refused before it starts if
   * it has used up its failures for the window (RPS-1092), and a failed check counts against it. An
   * unknown username is counted like a wrong password, so the two stay indistinguishable (RPS-906).
   */
  private void checkPassword(final @Nullable UserInfo userInfo, final @NonNull String password) {

    this.authFailureThrottle.checkAllowed();

    if (userInfo == null) {
      // Spend the time of a real check, so an unknown username is as slow as a wrong password.
      PasswordHasher.verifyDummy(password);
      this.authFailureThrottle.recordFailure();
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    // A client that sends Basic credentials on every request would otherwise pay one BCrypt check
    // per request (RPS-1025). Only a successful check is remembered, so this path costs what it did
    // before for a wrong password.
    if (!this.verifiedPasswordCache.matches(userInfo, password)) {
      this.authFailureThrottle.recordFailure();
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private boolean isRemembered(final @NonNull UserInfo userInfo, final @NonNull String password) {

    return !this.authFailureThrottle.isSaturated()
        && this.verifiedPasswordCache.isRemembered(userInfo, password);
  }

  private @NonNull RepoPermissionInfo authorizeRepoUser(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String authHeader,
      final @NonNull Permission permission) {

    final var userInfo = this.authenticateUser(authHeader);
    final var permissionInfo = this.authorizePanelUser(userInfo, permission);

    return RepoPermissionInfo.builder()
        .repoName(repoInfo.getName())
        .description(repoInfo.getDescription())
        ._private(repoInfo.isPrivateRepo())
        .canRead(permissionInfo.isCanRead())
        .canWrite(permissionInfo.isCanWrite())
        .canManage(permissionInfo.isCanManage())
        .build();
  }

  private @NonNull UserInfo authenticateWithBasic(final @NonNull String authHeader) {

    final var credentials = extractCredentialsFromAuthHeader(authHeader);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return this.authenticateWithPassword(credentials);
  }

  private @NonNull UserInfo authenticateWithBearer(final @NonNull String authHeader) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader, TokenRealm.PANEL);

    return this.userTxService.getAuthenticatedUserByUsername(username);
  }

  /**
   * Repos have no owner or per-user access list, so an authenticated user holds READ and WRITE on
   * every repo, private ones included; "private" only means "login required". Only MANAGE needs the
   * ADMIN role. The README documents this model (RPS-939), and {@code ProtocolAuthServiceTest} pins
   * it.
   */
  private void checkPermission(
      final @NonNull UserInfo userInfo, final @NonNull Permission permission) {

    switch (permission) {
      case READ, WRITE -> {
        /* All authenticated users have read/write permission */
      }
      case MANAGE -> this.checkManage(userInfo);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void checkManage(final @NonNull UserInfo userInfo) {

    if (userInfo.getRole() != UserRole.ADMIN) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }
}
