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
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.protocols.golang.shared.exceptions.GoVersionGoneException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ValidationException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class ErrorHandler {

  private static final int UNPROCESSABLE_ENTITY = 422;

  private static final @NonNull String ERR_BAD_REQUEST = "badRequest";
  private static final @NonNull String ERR_VALIDATION = "validationError";
  private static final @NonNull String ERR_ITEM_NOT_FOUND = "itemNotFound";
  private static final @NonNull String ERR_ERROR_OCCURRED = "errorOccurred";
  private static final @NonNull String ERR_METHOD_NOT_SUPPORTED = "methodNotSupported";
  private static final @NonNull String ERR_ACCESS_NOT_ALLOWED = "accessNotAllowed";
  private static final @NonNull String ERR_UNAUTHORIZED = "unauthorizedRequest";
  private static final @NonNull String ERR_ITEM_ALREADY_EXISTS = "itemAlreadyExists";
  private static final @NonNull String ERR_MOVED_TO_PATH = "movedToPath";
  private static final @NonNull String ERR_MFA_EXCEPTION = "mfaException";
  private static final @NonNull String ERR_SIGNATURE_NOT_VERIFIED = "artifactSignatureNotVerified";
  private static final @NonNull String ERR_MISSING_REQUEST_HEADER = "missingRequestHeader";
  private static final @NonNull String ERR_ILLEGAL_ARGUMENT = "Invalid method argument";

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

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpRequestMethodNotSupportedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Wrong mime type", ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
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

  @ExceptionHandler(GoVersionGoneException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull GoVersionGoneException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Go version gone", ex);
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.GONE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error("versionGone", ex.getMessage()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull NoResourceFoundException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Item not found", ex);

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
      log.debug(ERR_ILLEGAL_ARGUMENT, ex);

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
      log.debug(ERR_ILLEGAL_ARGUMENT, ex);

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
      log.debug(ERR_ILLEGAL_ARGUMENT, ex);

      return null;
    }

    if (ex.getHeaders() != null) {
      ex.getHeaders().forEach(response::addHeader);
    }

    log.info(exceptionToString(ex, request));

    final var exceptionMessage = ex.getMessage();
    final var messageText = exceptionMessage != null ? exceptionMessage : ERR_UNAUTHORIZED;

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(messageText, ex.getMessage()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @Nullable ResponseEntity<RestResponse<String>> handleException(
      final @NonNull HttpMediaTypeNotSupportedException ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug(ERR_ILLEGAL_ARGUMENT, ex);

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_VALIDATION));
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
   * Handles a missing required request header. A missing {@code Authorization} header stays a 403
   * like every other failed authentication on the panel (malformed, non-Bearer and expired tokens),
   * which is what the frontend interceptors expect; any other missing header is a plain 400.
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

    final var status =
        HttpHeaders.AUTHORIZATION.equalsIgnoreCase(ex.getHeaderName())
            ? HttpStatus.FORBIDDEN
            : HttpStatus.BAD_REQUEST;

    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.resp.error(ERR_MISSING_REQUEST_HEADER, ex.getHeaderName()));
  }
}
