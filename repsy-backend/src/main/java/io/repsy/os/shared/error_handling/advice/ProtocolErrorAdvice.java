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
package io.repsy.os.shared.error_handling.advice;

import static io.repsy.core.error_handling.utils.ErrorUtils.exceptionToString;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.RedirectToPathException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.web_error.ConstraintViolations;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.StorageUnavailableException;
import io.repsy.os.shared.error_handling.services.ErrorResponseService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Answers the failures the services, the protocol handlers and the data layer raise (RPS-2063): the
 * domain exceptions of {@code core-error-handling}, storage and database failures, the failed-login
 * throttle, and every exception no other advice names. The body is the {@code RestResponse}
 * envelope that {@code OciErrorBodyAdvice} and {@code CargoErrorBodyAdvice} rewrite for their
 * protocols, or an RFC 9457 problem when a panel controller served the request.
 *
 * <p>Ordered last: it holds the {@code Throwable} fallback, so every other advice gets the first
 * chance at an exception.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProtocolErrorAdvice {

  private static final HttpStatusCode UNPROCESSABLE_ENTITY = HttpStatusCode.valueOf(422);

  private final @NonNull ErrorResponseService errors;

  @Autowired
  public ProtocolErrorAdvice(final @NonNull ErrorResponseService errors) {
    this.errors = errors;
  }

  @ExceptionHandler(AccessNotAllowedException.class)
  @Nullable ResponseEntity<Object> handleAccessNotAllowed(
      final @NonNull AccessNotAllowedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Access not allowed: {}", exceptionToString(ex, request));

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.ACCESS_NOT_ALLOWED;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.FORBIDDEN, messageText, ex.getMessage()));
  }

  @ExceptionHandler(InvalidStoragePathException.class)
  @Nullable ResponseEntity<Object> handleInvalidStoragePath(
      final @NonNull InvalidStoragePathException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid storage path: {}", exceptionToString(ex, request));
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.INVALID_STORAGE_PATH,
                ProtocolErrorCodes.INVALID_STORAGE_PATH));
  }

  @ExceptionHandler(BadRequestException.class)
  @Nullable ResponseEntity<Object> handleBadRequest(
      final @NonNull BadRequestException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Bad request: {}", exceptionToString(ex, request));

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.BAD_REQUEST;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.BAD_REQUEST, messageText, ex.getMessage()));
  }

  @ExceptionHandler(SignatureNotVerifiedException.class)
  @Nullable ResponseEntity<Object> handleSignatureNotVerified(
      final @NonNull SignatureNotVerifiedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.warn("PGP Verification Exception: {}", exceptionToString(ex, request));

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null
            ? exceptionMessage
            : ProtocolErrorCodes.ARTIFACT_SIGNATURE_NOT_VERIFIED;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(UNPROCESSABLE_ENTITY)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, UNPROCESSABLE_ENTITY, messageText, ex.getMessage()));
  }

  @ExceptionHandler(ErrorOccurredException.class)
  @Nullable ResponseEntity<Object> handleErrorOccurred(
      final @NonNull ErrorOccurredException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("An error occurred: {}", exceptionToString(ex, request));

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.ERROR_OCCURRED;

    log.error(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.INTERNAL_SERVER_ERROR, messageText, ex.getMessage()));
  }

  @ExceptionHandler(RedirectToPathException.class)
  @Nullable ResponseEntity<Object> handleRedirectToPath(
      final @NonNull RedirectToPathException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Redirect to path: {}", exceptionToString(ex, request));

      return null;
    }

    final var headers = new HttpHeaders();
    headers.add("Location", ex.getPath());

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .contentType(this.errors.contentType(request))
        .headers(headers)
        .body(
            this.errors.error(
                request, HttpStatus.MOVED_PERMANENTLY, ProtocolErrorCodes.MOVED_TO_PATH));
  }

  @ExceptionHandler(ItemNotFoundException.class)
  @Nullable ResponseEntity<Object> handleItemNotFound(
      final @NonNull ItemNotFoundException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Item not found: {}", exceptionToString(ex, request));

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.ITEM_NOT_FOUND;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.NOT_FOUND, messageText, ex.getMessage()));
  }

  @ExceptionHandler(UnAuthorizedException.class)
  @Nullable ResponseEntity<Object> handleUnAuthorized(
      final @NonNull UnAuthorizedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Unauthorized request: {}", exceptionToString(ex, request));

      return null;
    }

    if (ex.getHeaders() != null) {
      ex.getHeaders().forEach(response::addHeader);
    }

    if (this.errors.isPanelRequest(request)
        && !response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
      response.addHeader(HttpHeaders.WWW_AUTHENTICATE, ErrorResponseService.PANEL_AUTH_CHALLENGE);
    }

    log.info(exceptionToString(ex, request));

    return this.unauthorizedBody(request, ex);
  }

  /**
   * The panel gets its own id and text for a 401 (RPS-1352): it means the credential is missing or
   * invalid, or its account is gone, as a signed-in user without the permission gets a 403 {@code
   * accessDenied} there (RPS-1268). Every other id, and the wire answer with the shared {@code
   * unAuthorized} id, stay as they are.
   */
  private ResponseEntity<Object> unauthorizedBody(
      final @NonNull HttpServletRequest request, final @NonNull UnAuthorizedException ex) {

    final var exceptionMessage = ex.getMessage();

    if (ProtocolErrorCodes.UN_AUTHORIZED.equals(exceptionMessage)
        && this.errors.isPanelRequest(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .contentType(this.errors.contentType(request))
          .body(
              this.errors.error(
                  request,
                  HttpStatus.UNAUTHORIZED,
                  ProtocolErrorCodes.LOGIN_REQUIRED,
                  exceptionMessage));
    }

    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.UNAUTHORIZED_REQUEST;

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.UNAUTHORIZED, messageText, ex.getMessage()));
  }

  /**
   * Answers a client that made too many failed password checks with 429 and the seconds until it
   * may try again. No {@code WWW-Authenticate} challenge is sent, so a package manager does not
   * prompt for credentials again and again. The answer is the same whatever username or password
   * was sent (RPS-906), and it is not logged per request, since a flood would fill the log.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(TooManyRequestsException.class)
  @Nullable ResponseEntity<Object> handleTooManyRequests(
      final @NonNull TooManyRequestsException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Too many requests: {}", exceptionToString(ex, request));
      return null;
    }

    log.debug("Too many failed authentication attempts: {}", request.getRemoteAddr());

    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(this.errors.contentType(request))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
        .body(
            this.errors.error(
                request, HttpStatus.TOO_MANY_REQUESTS, ProtocolErrorCodes.TOO_MANY_REQUESTS));
  }

  @ExceptionHandler(ItemAlreadyExistException.class)
  @Nullable ResponseEntity<Object> handleItemAlreadyExist(
      final @NonNull ItemAlreadyExistException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Item already exists: {}", exceptionToString(ex, request));
      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.ITEM_ALREADY_EXISTS;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.CONFLICT, messageText, ex.getMessage()));
  }

  /**
   * Handles a database constraint violation that reached the controller layer. Two kinds are
   * something the client can correct, so they answer 4xx: a value longer than its column (400
   * {@code validationError}) and a duplicate key (409 {@code itemAlreadyExists}), for example the
   * loser of a race that an up-front existence check could not close. Every other violation
   * (not-null, foreign key, check) means the server wrote something it should not have, so it stays
   * a 500 through {@link #handleUnexpectedException}, which logs it as an error.
   *
   * <p>The other class-22 (data exception) states stay a 500 on purpose (RPS-1080), among them
   * 22003 numeric value out of range, 22P02 and 22018 invalid text representation, 22007 invalid
   * datetime format and 22012 division by zero. No request can cause them today: every numeric
   * column is as wide as its Java field, so binding is the range check, no query casts or divides a
   * request value, and Spring already answers 400 for a malformed path variable or parameter. One
   * that occurs is therefore a server bug, and a 500 keeps exposing it. PostgreSQL and H2 also
   * spell the same fault differently (integer overflow is 22003 on PostgreSQL and 22004 on H2; a
   * text that does not cast is 22P02 and 22018), so a state added here later has to be added for
   * both databases.
   *
   * <p>A mapped violation still means an up-front check missed a case, so it is logged as a warning
   * with the SQL state. The constraint name and offending value stay out of the response.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  @Nullable ResponseEntity<Object> handleDataIntegrityViolation(
      final @NonNull DataIntegrityViolationException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Data integrity violation: {}", exceptionToString(ex, request));
      return null;
    }

    final var sqlState = ConstraintViolations.sqlState(ex);

    final HttpStatus status;
    final String msgId;

    if (ConstraintViolations.SQL_STATE_VALUE_TOO_LONG.equals(sqlState)) {
      status = HttpStatus.BAD_REQUEST;
      msgId = ProtocolErrorCodes.VALIDATION_ERROR;
    } else if (ConstraintViolations.SQL_STATE_UNIQUE_VIOLATION.equals(sqlState)) {
      status = HttpStatus.CONFLICT;
      msgId = ProtocolErrorCodes.ITEM_ALREADY_EXISTS;
    } else {
      return this.handleUnexpectedException(ex, request, response);
    }

    log.warn("Constraint violation (SQL state {}): {}", sqlState, exceptionToString(ex, request));

    return ResponseEntity.status(status)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, status, msgId));
  }

  /**
   * Handles a write that lost an optimistic-lock race: another request changed the same row (an
   * entity with a {@code @Version}) between this request's read and its commit, so the version
   * check matched no row. It covers {@code ObjectOptimisticLockingFailureException}, which is what
   * Spring translates that failure to, and a raw {@code
   * jakarta.persistence.OptimisticLockException} that reaches the handler without Spring's
   * translation (an {@code EntityManager} used outside a repository). Nothing of the losing request
   * was written (its transaction rolled back) and the same request repeated normally succeeds, so
   * it is not a server error (RPS-1325, RPS-1342). Both answers carry the same {@code
   * concurrentModification} message id.
   *
   * <p>A panel API request gets 409: the client re-reads and repeats. A request on a protocol port
   * (the OCI {@code /v2/} endpoints and every other package format) gets 503 with a {@code
   * Retry-After} instead (RPS-1355). On a package protocol 409 already means "this version exists",
   * so {@code twine --skip-existing} and {@code dotnet nuget push --skip-duplicate} would take a
   * lost race for a duplicate and skip the push, while npm, pip, Maven, Cargo, NuGet and {@code
   * crane} retry a 503. On the OCI endpoints the distribution specification maps a 409 to {@code
   * DENIED}, which tells the user they lack access, and a client that retries anything retries a
   * 5xx. Checked against real clients (RPS-1342): {@code crane} repeats a manifest PUT that was
   * answered 503 (after its own 1 s and 3 s backoff, it does not read the {@code Retry-After}
   * value) and the push then succeeds; the {@code docker} CLI does not repeat the manifest PUT, it
   * stops with {@code received unexpected HTTP status: 503}, and the user pushes again. The push
   * handler already repeats the save a few times, so this answer means a heavily contended tag,
   * which is exactly what a later retry resolves. The body stays in the distribution format through
   * {@link OciErrorBodyAdvice}. 429 was not chosen because nothing here is rate limiting.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
  @Nullable ResponseEntity<Object> handleOptimisticLockFailure(
      final @NonNull Exception ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Optimistic lock failure: {}", exceptionToString(ex, request));
      return null;
    }

    log.warn("Optimistic lock failure: {}", exceptionToString(ex, request));

    if (!this.errors.isPanelRequest(request)) {
      return this.errors.retryLater(
          this.errors.error(
              request, HttpStatus.SERVICE_UNAVAILABLE, ProtocolErrorCodes.CONCURRENT_MODIFICATION),
          request);
    }

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.CONFLICT, ProtocolErrorCodes.CONCURRENT_MODIFICATION));
  }

  /**
   * Handles a statement that could not get a database lock: it timed out waiting for a row that
   * another transaction holds ({@code CannotAcquireLockException}), the database picked it as the
   * victim of a deadlock ({@code DeadlockLoserDataAccessException}), or a serializable transaction
   * could not be ordered ({@code CannotSerializeTransactionException}). All three are subclasses of
   * Spring's {@code PessimisticLockingFailureException}; the raw {@code
   * jakarta.persistence.PessimisticLockException} and {@code LockTimeoutException} that reach the
   * handler without Spring's translation are treated the same. A {@code PESSIMISTIC_WRITE} that
   * waits too long (RPS-1273 takes one per chart) must not surface as a server error (RPS-1342).
   *
   * <p>The answer is 503 with {@code Retry-After} on every endpoint, not the 409 of an optimistic
   * failure. A 409 says the request collides with the current state of the item and the client
   * should look at it again; here the item did not change under the request, the server was only
   * momentarily unable to serve it, and the identical request repeated a moment later succeeds.
   * That is what 503 with {@code Retry-After} means, and generic HTTP clients (and registry
   * clients, which retry a 5xx and give up on a 409) act on it without any knowledge of this API.
   * The message id differs from {@code concurrentModification} because nothing was changed by
   * another request.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler({
    PessimisticLockingFailureException.class,
    PessimisticLockException.class,
    LockTimeoutException.class
  })
  @Nullable ResponseEntity<Object> handleLockUnavailable(
      final @NonNull Exception ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Database lock unavailable: {}", exceptionToString(ex, request));
      return null;
    }

    log.warn("Database lock unavailable: {}", exceptionToString(ex, request));

    return this.errors.retryLater(
        this.errors.error(
            request, HttpStatus.SERVICE_UNAVAILABLE, ProtocolErrorCodes.RESOURCE_BUSY),
        request);
  }

  /**
   * Handles a storage write that failed (RPS-2104): the storage strategy could not create, write or
   * move the object of an upload. The request kept nothing, so the client is told to repeat it: 503
   * with {@code Retry-After}, like a lock that could not be taken. The message id stays {@code
   * errorOccurred}, the one this failure answered as a 500 before, and the body goes the format's
   * own way (the RestResponse envelope that the OCI and Cargo advices rewrite, problem+json on the
   * panel).
   *
   * <p>Only {@link StorageUnavailableException} is mapped, never a bare {@code IOException}: reads,
   * a client that went away mid-upload and everything else keep the 500 of {@link
   * #defaultExceptionHandler}. A failure that does not pass with time (a full disk) is answered as
   * retry-later too; it is logged as an error with its cause so that it is seen.
   */
  @ExceptionHandler(StorageUnavailableException.class)
  @Nullable ResponseEntity<Object> handleStorageUnavailable(
      final @NonNull StorageUnavailableException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Storage unavailable: {}", exceptionToString(ex, request));
      return null;
    }

    log.error(exceptionToString(ex, request));

    if (response.isCommitted()) {
      log.warn("Response already committed, skipping error body write for a storage failure");
      return null;
    }

    response.resetBuffer();

    return this.errors.retryLater(
        this.errors.error(
            request, HttpStatus.SERVICE_UNAVAILABLE, ProtocolErrorCodes.ERROR_OCCURRED),
        request);
  }

  @ExceptionHandler(RetryableException.class)
  @Nullable ResponseEntity<Object> handleRetryable(
      final @NonNull RetryableException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Retryable request failure: {}", exceptionToString(ex, request));
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(this.errors.contentType(request))
        .header(HttpHeaders.RETRY_AFTER, ErrorResponseService.LOCK_FAILURE_RETRY_AFTER)
        .body(
            this.errors.error(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                ProtocolErrorCodes.SCAN_EXECUTOR_SATURATED,
                ProtocolErrorCodes.SCAN_EXECUTOR_SATURATED));
  }

  /**
   * The answer of an exception no other handler names: 500 {@code errorOccurred}. The exception is
   * logged, never put in the response.
   */
  @ExceptionHandler(Throwable.class)
  @Nullable ResponseEntity<Object> handleUnexpectedException(
      final @NonNull Throwable ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    return this.errors.fallback(ex, request, response);
  }
}
