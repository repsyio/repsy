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

import io.repsy.core.error_handling.exceptions.MfaException;
import io.repsy.core.web.paging.InvalidPagingParameterException;
import io.repsy.os.shared.error_handling.services.ErrorResponseService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ValidationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers the failures of a request itself (RPS-2063): a required value left out, an unreadable
 * body, a constraint or conversion that failed, a method or media type the endpoint does not take,
 * and the MFA failure of the panel login. The body is an RFC 9457 problem on a panel request and
 * the {@code RestResponse} envelope on any other, which {@link ErrorResponseService} decides.
 *
 * <p>Ordered before {@link ProtocolErrorAdvice}, which holds the {@code Throwable} fallback; the
 * handled types of the two are disjoint, which {@code ErrorAdviceOrderTest} pins.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class PanelProblemDetailAdvice {

  private final ErrorResponseService errors;

  @Autowired
  public PanelProblemDetailAdvice(final ErrorResponseService errors) {
    this.errors = errors;
  }

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
  @Nullable ResponseEntity<Object> handleMissingRequestValue(
      final Exception ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Missing request value: {}", exceptionToString(ex, request));
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.BAD_REQUEST, ProtocolErrorCodes.BAD_REQUEST, missingName(ex)));
  }

  @ExceptionHandler(ConversionFailedException.class)
  @Nullable ResponseEntity<Object> handleConversionFailed(
      final ConversionFailedException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Conversion failed: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.BAD_REQUEST, ProtocolErrorCodes.VALIDATION_ERROR));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @Nullable ResponseEntity<Object> handleMethodArgumentTypeMismatch(
      final MethodArgumentTypeMismatchException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method argument type mismatch: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                ex.getName()));
  }

  /**
   * Handles constraint violations on controller method parameters (for example a {@code Min}
   * constraint on a request parameter), answering with the names of the offending parameters.
   *
   * @param ex Thrown method validation exception
   * @return REST response
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  @Nullable ResponseEntity<Object> handleHandlerMethodValidation(
      final HandlerMethodValidationException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method validation failed: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    final var invalidParameters =
        ex.getParameterValidationResults().stream()
            .map(result -> result.getMethodParameter().getParameterName())
            .collect(Collectors.joining(","));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                invalidParameters,
                ErrorResponseService.fieldsOf(ex)));
  }

  /**
   * Handles an unacceptable {@code page}, {@code size} or {@code sort} on a paged endpoint,
   * answering like a constraint violation on an explicit request parameter does.
   *
   * @param ex Thrown paging exception
   * @return REST response
   */
  @ExceptionHandler(InvalidPagingParameterException.class)
  @Nullable ResponseEntity<Object> handleInvalidPagingParameter(
      final InvalidPagingParameterException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid paging parameter: {}", exceptionToString(ex, request));
      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                ex.getParameterNames()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @Nullable ResponseEntity<Object> handleMessageNotReadable(
      final HttpMessageNotReadableException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Malformed body request: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.BAD_REQUEST, ProtocolErrorCodes.VALIDATION_ERROR));
  }

  /**
   * Handles a request method the endpoint does not support, answering 405 with an {@code Allow}
   * header listing the methods it does.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @Nullable ResponseEntity<Object> handleRequestMethodNotSupported(
      final HttpRequestMethodNotSupportedException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("HTTP method not supported: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .headers(ex.getHeaders())
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.METHOD_NOT_ALLOWED, ProtocolErrorCodes.METHOD_NOT_SUPPORTED));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  @Nullable ResponseEntity<Object> handleNoResourceFound(
      final NoResourceFoundException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Resource not found: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.NOT_FOUND, ProtocolErrorCodes.ITEM_NOT_FOUND));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
      final MethodArgumentNotValidException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Method argument not valid: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                null,
                ErrorResponseService.fieldsOf(ex)));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
      final MissingServletRequestParameterException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Missing request parameter: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                ex.getParameterName()));
  }

  /**
   * Handles a request body with a content type the endpoint cannot read, answering 415 with an
   * {@code Accept} header listing the content types it can.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @Nullable ResponseEntity<Object> handleMediaTypeNotSupported(
      final HttpMediaTypeNotSupportedException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Media type not supported: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .headers(ex.getHeaders())
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ProtocolErrorCodes.UNSUPPORTED_MEDIA_TYPE));
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
  @Nullable ResponseEntity<Object> handleMediaTypeNotAcceptable(
      final HttpMediaTypeNotAcceptableException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Media type not acceptable: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
        .headers(ex.getHeaders())
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.NOT_ACCEPTABLE, ProtocolErrorCodes.NOT_ACCEPTABLE));
  }

  /**
   * Handles a multipart upload over the configured size limit, answering 413 instead of a server
   * error because the client sent too much.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @Nullable ResponseEntity<Object> handleMaxUploadSizeExceeded(
      final MaxUploadSizeExceededException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Upload too large: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, HttpStatus.PAYLOAD_TOO_LARGE, ProtocolErrorCodes.PAYLOAD_TOO_LARGE));
  }

  /**
   * Handles a request that misses the request parameters a {@code params} condition on the mapping
   * asks for, answering 400 like Spring's default resolver does.
   *
   * @param ex Thrown exception
   * @return REST response
   */
  @ExceptionHandler(UnsatisfiedServletRequestParameterException.class)
  @Nullable ResponseEntity<Object> handleUnsatisfiedServletRequestParameter(
      final UnsatisfiedServletRequestParameterException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Request parameter condition not satisfied: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.BAD_REQUEST, ProtocolErrorCodes.BAD_REQUEST));
  }

  /**
   * Returns form validation errors from controller validations
   *
   * @param ex Thrown validation exception
   * @return REST response
   */
  @ExceptionHandler(ValidationException.class)
  @Nullable ResponseEntity<Object> handleValidation(
      final ValidationException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Invalid request: {}", exceptionToString(ex, request));

      return null;
    }

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request,
                HttpStatus.BAD_REQUEST,
                ProtocolErrorCodes.VALIDATION_ERROR,
                null,
                ErrorResponseService.fieldsOf(ex)));
  }

  @ExceptionHandler(MfaException.class)
  @Nullable ResponseEntity<Object> handleMfa(
      final MfaException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("MFA Exception: {}", exceptionToString(ex, request));
      return null;
    }

    final var exceptionMessage = ex.getMessage();
    final var messageText =
        exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.MFA_EXCEPTION;

    log.info(exceptionToString(ex, request));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(this.errors.contentType(request))
        .body(this.errors.error(request, HttpStatus.BAD_REQUEST, messageText, ex.getMessage()));
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
  @Nullable ResponseEntity<Object> handleMissingRequestHeader(
      final MissingRequestHeaderException ex,
      final HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("Request Header resolution failed: {}", exceptionToString(ex, request));
      return null;
    }

    log.info(exceptionToString(ex, request));

    final var authorizationMissing = HttpHeaders.AUTHORIZATION.equalsIgnoreCase(ex.getHeaderName());

    if (authorizationMissing && this.errors.isPanelRequest(request)) {
      response.addHeader(HttpHeaders.WWW_AUTHENTICATE, ErrorResponseService.PANEL_AUTH_CHALLENGE);
    }

    final var status = authorizationMissing ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;

    return ResponseEntity.status(status)
        .contentType(this.errors.contentType(request))
        .body(
            this.errors.error(
                request, status, ProtocolErrorCodes.MISSING_REQUEST_HEADER, ex.getHeaderName()));
  }

  /** The name of the missing value, never the framework text (RPS-2162). */
  private static String missingName(final Exception ex) {
    return switch (ex) {
      case final MissingRequestCookieException cookie -> cookie.getCookieName();
      case final MissingMatrixVariableException variable -> variable.getVariableName();
      case final MissingServletRequestPartException part -> part.getRequestPartName();
      default -> ProtocolErrorCodes.BAD_REQUEST;
    };
  }
}
