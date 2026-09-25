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
package io.repsy.os.shared.error_handling.services;

import static io.repsy.core.error_handling.utils.ErrorUtils.exceptionToString;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.MfaException;
import io.repsy.core.error_handling.exceptions.RedirectToPathException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.error_handling.exceptions.InvalidPagingParameterException;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.error_handling.utils.OciErrors;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ValidationException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class ErrorHandler {

  private static final int UNPROCESSABLE_ENTITY = 422;

  private static final @NonNull String PANEL_AUTH_CHALLENGE = "Bearer";

  private static final @NonNull String ERR_BAD_REQUEST = "badRequest";
  private static final @NonNull String ERR_VALIDATION = "validationError";
  private static final @NonNull String ERR_ITEM_NOT_FOUND = "itemNotFound";
  private static final @NonNull String ERR_ERROR_OCCURRED = "errorOccurred";
  private static final @NonNull String ERR_METHOD_NOT_SUPPORTED = "methodNotSupported";
  private static final @NonNull String ERR_UNSUPPORTED_MEDIA_TYPE = "unsupportedMediaType";
  private static final @NonNull String ERR_NOT_ACCEPTABLE = "notAcceptable";
  private static final @NonNull String ERR_PAYLOAD_TOO_LARGE = "payloadTooLarge";
  private static final @NonNull String ERR_ACCESS_NOT_ALLOWED = "accessNotAllowed";
  private static final @NonNull String ERR_UNAUTHORIZED = "unauthorizedRequest";
  private static final @NonNull String ERR_PANEL_LOGIN_REQUIRED = "loginRequired";
  private static final @NonNull String ERR_ITEM_ALREADY_EXISTS = "itemAlreadyExists";
  private static final @NonNull String ERR_MOVED_TO_PATH = "movedToPath";
  private static final @NonNull String ERR_MFA_EXCEPTION = "mfaException";
  private static final @NonNull String ERR_SIGNATURE_NOT_VERIFIED = "artifactSignatureNotVerified";
  private static final @NonNull String ERR_MISSING_REQUEST_HEADER = "missingRequestHeader";
  private static final @NonNull String ERR_SCAN_EXECUTOR_SATURATED = "scanExecutorSaturated";
  private static final @NonNull String ERR_TOO_MANY_REQUESTS = "tooManyRequests";
  private static final @NonNull String ERR_CONCURRENT_MODIFICATION = "concurrentModification";
  private static final @NonNull String ERR_RESOURCE_BUSY = "resourceBusy";

  /** Seconds a client is told to wait before it repeats a request that lost a lock race. */
  private static final @NonNull String LOCK_FAILURE_RETRY_AFTER = "1";

  private final @NonNull RestResponseFactory resp;

  private static final @NonNull Set<String> NOT_LOGGED_EXCEPTIONS =
      Set.of(
          "org.apache.catalina.connector.ClientAbortException",
          "java.nio.channels.ClosedChannelException",
          "org.springframework.web.context.request.async.AsyncRequestNotUsableException");

  /**
   * Handles required request values the client left out (cookie, matrix variable, multipart part).
   * Path variables are deliberately not listed: a missing one means the handler mapping is wrong,
   * which is a server error and stays a 500. Headers and request parameters have their own
   * handlers.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler({
    MissingRequestCookieException.class,
    MissingMatrixVariableException.class,
    MissingServletRequestPartException.class
  })
  @Nullable ResponseEntity<RestResponse<String>> handleMissingRequestValue(
      final @NonNull Exception ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Missing request value", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_BAD_REQUEST, ex.getMessage()));
  }

  @ExceptionHandler(Throwable.class)
  @Nullable ResponseEntity<RestResponse<Object>> defaultExceptionHandler(
      final @NonNull Throwable ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("An exception occurred", ex);
      return null;
    }

    if (ex instanceof @NonNull final HttpClientErrorException exception) {
      log.error(exception.getResponseBodyAsString());
    }

    if (!NOT_LOGGED_EXCEPTIONS.contains(ex.getClass().getName())) {
      log.error(exceptionToString(ex, request));
    }

    if (response.isCommitted()) {
      log.warn(
          "Response already committed, skipping error body write for {}", ex.getClass().getName());
      return null;
    }

    response.resetBuffer();

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_ERROR_OCCURRED));
  }

  @ExceptionHandler(AccessNotAllowedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull AccessNotAllowedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Access not allowed", ex);

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_ACCESS_NOT_ALLOWED;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(InvalidStoragePathException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull InvalidStoragePathException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid storage path", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error("invalidStoragePath", ex.getMessage()));
  }

  @ExceptionHandler(BadRequestException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull BadRequestException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Bad request", ex);

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_BAD_REQUEST;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(SignatureNotVerifiedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull SignatureNotVerifiedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.warn("PGP Verification Exception", ex);

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ERR_SIGNATURE_NOT_VERIFIED;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(ConversionFailedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull ConversionFailedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Conversion failed", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_ITEM_NOT_FOUND, ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull MethodArgumentTypeMismatchException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method argument type mismatch", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION, ex.getName()));
  }

  /**
   * Handles constraint violations on controller method parameters (for example a {@code Min}
   * constraint on a request parameter), answering with the names of the offending parameters.
   *
   * @param ex Thrown method validation exception
   * @return REST response
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HandlerMethodValidationException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method validation failed", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    final var invalidParameters =
        ex.getParameterValidationResults().stream()
            .map(result -> result.getMethodParameter().getParameterName())
            .collect(Collectors.joining(","));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION, invalidParameters));
  }

  /**
   * Handles an unacceptable {@code page}, {@code size} or {@code sort} on a paged endpoint,
   * answering like a constraint violation on an explicit request parameter does.
   *
   * @param ex Thrown paging exception
   * @return REST response
   */
  @ExceptionHandler(InvalidPagingParameterException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull InvalidPagingParameterException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid paging parameter", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION, ex.getParameterNames()));
  }

  @ExceptionHandler(ErrorOccurredException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull ErrorOccurredException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("An error occurred", ex);

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_ERROR_OCCURRED;

    log.error(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(RedirectToPathException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull RedirectToPathException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Redirect to path", ex);

      return null;
    }

    final var headers = new HttpHeaders();
    headers.add("Location", ex.getPath());

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers)
        .body(this.resp.error(ERR_MOVED_TO_PATH));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpMessageNotReadableException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Malformed body request", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION));
  }

  /**
   * Handles a request method the endpoint does not support, answering 405 with an {@code Allow}
   * header listing the methods it does.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpRequestMethodNotSupportedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("HTTP method not supported", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .headers(ex.getHeaders())
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_METHOD_NOT_SUPPORTED));
  }

  @ExceptionHandler(ItemNotFoundException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull ItemNotFoundException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Item not found", ex);

      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_ITEM_NOT_FOUND;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull NoResourceFoundException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Resource not found", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_ITEM_NOT_FOUND));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @Nullable ResponseEntity<RestResponse<Void>> handleException(
      final @NonNull MethodArgumentNotValidException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method argument not valid", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull MissingServletRequestParameterException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Missing request parameter", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION, ex.getParameterName()));
  }

  @ExceptionHandler(UnAuthorizedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull UnAuthorizedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Unauthorized request", ex);

      return null;
    }

    if (ex.getHeaders() != null) {
      ex.getHeaders().forEach(response::addHeader);
    }

    if (isPanelRequest(request) && !response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
      response.addHeader(HttpHeaders.WWW_AUTHENTICATE, PANEL_AUTH_CHALLENGE);
    }

    log.info(exceptionToString(ex, request));

    return this.unauthorizedBody(request, ex);
  }

  /**
   * The message of the shared {@code unAuthorized} id says the user is logged in but lacks the
   * permission, which is what a package manager is told on the wire. A panel 401 means the
   * opposite: the credential is missing or invalid, or its account is gone, as a signed-in user
   * without the permission gets a 403 {@code accessDenied} there (RPS-1268). The panel gets its own
   * id and text for it (RPS-1352); every other id, and the wire answer, stay as they are.
   */
  private ResponseEntity<RestResponse<String>> unauthorizedBody(
      final @NonNull HttpServletRequest request, final @NonNull UnAuthorizedException ex) {

    final var exceptionMessage = ex.getMessage();

    if (ErrorConstants.UN_AUTHORIZED.equals(exceptionMessage) && isPanelRequest(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .contentType(MediaType.APPLICATION_JSON)
          .body(this.resp.error(ERR_PANEL_LOGIN_REQUIRED, exceptionMessage));
    }

    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_UNAUTHORIZED;

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
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
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull TooManyRequestsException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Too many requests", ex);
      return null;
    }

    log.debug("Too many failed authentication attempts: {}", request.getRemoteAddr());

    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
        .body(this.resp.error(ERR_TOO_MANY_REQUESTS));
  }

  /**
   * Tells whether the failed request was served by a panel API controller. Those are the ones
   * carrying {@link RestApiPort}; the protocol endpoints on the main port announce their own
   * challenges (Basic, or a Docker Bearer realm) and must not get the panel's.
   */
  private static boolean isPanelRequest(final @NonNull HttpServletRequest request) {
    return request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof final HandlerMethod handler
        && (AnnotationUtils.findAnnotation(handler.getMethod(), RestApiPort.class) != null
            || AnnotationUtils.findAnnotation(handler.getBeanType(), RestApiPort.class) != null);
  }

  /**
   * Handles a request body with a content type the endpoint cannot read, answering 415 with an
   * {@code Accept} header listing the content types it can.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpMediaTypeNotSupportedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Media type not supported", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .headers(ex.getHeaders())
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_UNSUPPORTED_MEDIA_TYPE));
  }

  /**
   * Handles a request whose {@code Accept} header names no media type the endpoint can produce,
   * answering 406 with an {@code Accept} header listing the ones it can. The error body is written
   * as JSON regardless, because an explicit {@code Content-Type} skips content negotiation.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpMediaTypeNotAcceptableException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Media type not acceptable", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
        .headers(ex.getHeaders())
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_NOT_ACCEPTABLE));
  }

  /**
   * Handles a multipart upload over the configured size limit, answering 413 instead of a server
   * error because the client sent too much.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull MaxUploadSizeExceededException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Upload too large", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_PAYLOAD_TOO_LARGE));
  }

  /**
   * Handles a request that misses the request parameters a {@code params} condition on the mapping
   * asks for, answering 400 like Spring's default resolver does.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(UnsatisfiedServletRequestParameterException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull UnsatisfiedServletRequestParameterException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Request parameter condition not satisfied", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_BAD_REQUEST));
  }

  /**
   * Returns form validation errors from controller validations
   *
   * @param ex Thrown validation exception
   * @return REST response
   */
  @ExceptionHandler(ValidationException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull ValidationException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid request", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION, ex.getMessage()));
  }

  @ExceptionHandler(ItemAlreadyExistException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull ItemAlreadyExistException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Item already exists", ex);
      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_ITEM_ALREADY_EXISTS;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  /**
   * Handles a database constraint violation that reached the controller layer. Two kinds are
   * something the client can correct, so they answer 4xx: a value longer than its column (400
   * {@code validationError}) and a duplicate key (409 {@code itemAlreadyExists}), for example the
   * loser of a race that an up-front existence check could not close. Every other violation
   * (not-null, foreign key, check) means the server wrote something it should not have, so it stays
   * a 500 through {@link #defaultExceptionHandler}, which logs it as an error.
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
  @Nullable ResponseEntity<RestResponse<Object>> handleException(
      final @NonNull DataIntegrityViolationException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Data integrity violation", ex);
      return null;
    }

    final var sqlState = ConstraintViolations.sqlState(ex);

    final HttpStatus status;
    final String msgId;

    if (ConstraintViolations.SQL_STATE_VALUE_TOO_LONG.equals(sqlState)) {
      status = HttpStatus.BAD_REQUEST;
      msgId = ERR_VALIDATION;
    } else if (ConstraintViolations.SQL_STATE_UNIQUE_VIOLATION.equals(sqlState)) {
      status = HttpStatus.CONFLICT;
      msgId = ERR_ITEM_ALREADY_EXISTS;
    } else {
      return this.defaultExceptionHandler(ex, request, response);
    }

    log.warn("Constraint violation (SQL state {}): {}", sqlState, exceptionToString(ex, request));

    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(msgId));
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
   * <p>A panel or other protocol request gets 409: the client re-reads and repeats. A request on
   * the OCI {@code /v2/} endpoints gets 503 with a {@code Retry-After} instead. The distribution
   * specification maps a 409 to {@code DENIED}, which tells the user they lack access, and a client
   * that retries anything retries a 5xx. Checked against real clients (RPS-1342): {@code crane}
   * repeats a manifest PUT that was answered 503 (after its own 1 s and 3 s backoff, it does not
   * read the {@code Retry-After} value) and the push then succeeds; the {@code docker} CLI does not
   * repeat the manifest PUT, it stops with {@code received unexpected HTTP status: 503}, and the
   * user pushes again. The push handler already repeats the save a few times, so this answer means
   * a heavily contended tag, which is exactly what a later retry resolves. The body stays in the
   * distribution format through {@link OciErrorBodyAdvice}. 429 was not chosen because nothing here
   * is rate limiting.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
  @Nullable ResponseEntity<RestResponse<String>> handleOptimisticLockFailure(
      final @NonNull Exception ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Optimistic lock failure", ex);
      return null;
    }

    log.warn("Optimistic lock failure: {}", exceptionToString(ex, request));

    if (OciErrors.isOciRequest(request)) {
      return retryLater(this.resp.error(ERR_CONCURRENT_MODIFICATION));
    }

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_CONCURRENT_MODIFICATION));
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
  @Nullable ResponseEntity<RestResponse<String>> handleLockUnavailable(
      final @NonNull Exception ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Database lock unavailable", ex);
      return null;
    }

    log.warn("Database lock unavailable: {}", exceptionToString(ex, request));

    return retryLater(this.resp.error(ERR_RESOURCE_BUSY));
  }

  /** A 503 that tells the client to repeat the request after {@code Retry-After} seconds. */
  private static ResponseEntity<RestResponse<String>> retryLater(
      final @NonNull RestResponse<String> body) {

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.RETRY_AFTER, LOCK_FAILURE_RETRY_AFTER)
        .body(body);
  }

  @ExceptionHandler(RetryableException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull RetryableException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Retryable request failure", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_SCAN_EXECUTOR_SATURATED, ex.getMessage()));
  }

  @ExceptionHandler(MfaException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull MfaException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("MFA Exception", ex);
      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_MFA_EXCEPTION;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  /**
   * Handles a missing required request header. A missing {@code Authorization} header is a failed
   * authentication and answers 401 like the other panel authentication failures (malformed,
   * non-Bearer and expired tokens), announcing the Bearer scheme on a panel endpoint; any other
   * missing header is a plain 400.
   *
   * @param ex Thrown exception
   * @return REST response carrying the header name
   */
  @ExceptionHandler(MissingRequestHeaderException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull MissingRequestHeaderException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Request Header resolution failed", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    final var authorizationMissing = HttpHeaders.AUTHORIZATION.equalsIgnoreCase(ex.getHeaderName());

    if (authorizationMissing && isPanelRequest(request)) {
      response.addHeader(HttpHeaders.WWW_AUTHENTICATE, PANEL_AUTH_CHALLENGE);
    }

    return ResponseEntity.status(
            authorizationMissing ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_MISSING_REQUEST_HEADER, ex.getHeaderName()));
  }
}
