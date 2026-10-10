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

import com.google.common.base.Splitter;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.core.web_error.ProblemField;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * What the error advices of {@code io.repsy.os.shared.error_handling.advice} share (RPS-2063):
 * telling a panel request from a protocol request, building the failure body (an RFC 9457 problem
 * on the panel, the {@code RestResponse} envelope elsewhere), the retry-later answer and the
 * fallback of an exception no handler names.
 */
@Slf4j
@Service
public class ErrorResponseService {

  public static final @NonNull String PANEL_AUTH_CHALLENGE = "Bearer";

  /** Seconds a client is told to wait before it repeats a request that lost a lock race. */
  public static final @NonNull String LOCK_FAILURE_RETRY_AFTER = "1";

  private static final @NonNull Set<String> NOT_LOGGED_EXCEPTIONS =
      Set.of(
          "org.apache.catalina.connector.ClientAbortException",
          "java.nio.channels.ClosedChannelException",
          "org.springframework.web.context.request.async.AsyncRequestNotUsableException");

  private final @NonNull RestResponseFactory resp;

  /** Tells the panel port from the protocol ports; {@code null} where only the handler decides. */
  private final @Nullable MultiPortProperties multiPortProperties;

  @Autowired
  public ErrorResponseService(
      final @NonNull RestResponseFactory resp,
      final @Nullable MultiPortProperties multiPortProperties) {

    this.resp = resp;
    this.multiPortProperties = multiPortProperties;
  }

  /** For a unit test that has no port configuration: only the handler tells a panel request. */
  public ErrorResponseService(final @NonNull RestResponseFactory resp) {
    this(resp, null);
  }

  /**
   * Tells whether the failed request was served by a panel API controller. Those are the ones
   * carrying {@link RestApiPort}; the protocol endpoints on the main port announce their own
   * challenges (Basic, or a Docker Bearer realm) and must not get the panel's.
   */
  public boolean isPanelRequest(final @NonNull HttpServletRequest request) {
    if (request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
        instanceof final HandlerMethod handler) {
      return AnnotationUtils.findAnnotation(handler.getMethod(), RestApiPort.class) != null
          || AnnotationUtils.findAnnotation(handler.getBeanType(), RestApiPort.class) != null;
    }

    // No controller served the request (an unknown route, a method or media type no handler
    // accepts, a missing static file): the port it arrived on says whether it was the panel's.
    return this.arrivedOnPanelPort(request);
  }

  private boolean arrivedOnPanelPort(final @NonNull HttpServletRequest request) {
    final var props = this.multiPortProperties;

    if (props == null || props.getPorts() == null) {
      return false;
    }

    final var apiPort = props.getPorts().get(MultiPortNames.PORT_API);
    final var localPort = request.getLocalPort();

    return apiPort != null
        && (apiPort == localPort
            || MultiPortNames.PORT_API.equals(props.getPortAliases().get(localPort)));
  }

  /**
   * The content type of an error body: {@code application/problem+json} (RFC 9457) on a panel
   * request, the {@link RestResponse} JSON the protocol ports and the OCI advice expect otherwise.
   */
  public MediaType contentType(final @NonNull HttpServletRequest request) {
    return this.isPanelRequest(request)
        ? MediaType.APPLICATION_PROBLEM_JSON
        : MediaType.APPLICATION_JSON;
  }

  public Object error(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpStatusCode status,
      final @NonNull String msgId) {

    return this.error(request, status, msgId, null, List.of());
  }

  public Object error(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpStatusCode status,
      final @NonNull String msgId,
      final @Nullable String data) {

    return this.error(request, status, msgId, data, List.of());
  }

  /**
   * Builds the body of a failure. A panel request gets an RFC 9457 problem: {@code code} is the
   * message id the clients switch on, {@code traceId} the unique id of this failure, and {@code
   * errors} lists the offending fields (the {@code data} names, or the given fields). Any other
   * request gets the {@link RestResponse} envelope, which {@code OciErrorBodyAdvice} and the
   * protocol clients still read.
   */
  public Object error(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpStatusCode status,
      final @NonNull String msgId,
      final @Nullable String data,
      final @NonNull List<ProblemField> fields) {

    final RestResponse<String> envelope = this.resp.error(msgId, data);

    if (!this.isPanelRequest(request)) {
      return envelope;
    }

    final var problem = ProblemDetail.forStatus(status);
    problem.setDetail(envelope.getText());

    try {
      problem.setInstance(URI.create(request.getRequestURI()));
    } catch (final IllegalArgumentException ignored) {
      // A request path that is not a valid URI reference: the problem simply has no instance.
    }

    problem.setProperty("code", envelope.getMsgId());

    final var errors = fields.isEmpty() ? namedFields(msgId, data, envelope.getText()) : fields;

    if (!errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }

    problem.setProperty("traceId", envelope.getErrorCode());

    return problem;
  }

  private static boolean isFieldList(final @NonNull String data, final @NonNull String msgId) {
    return !data.isBlank() && !data.equals(msgId) && !data.contains(" ");
  }

  private static @NonNull List<ProblemField> namedFields(
      final @NonNull String msgId, final @Nullable String data, final @Nullable String text) {

    if (data == null
        || !(ProtocolErrorCodes.VALIDATION_ERROR.equals(msgId)
            || ProtocolErrorCodes.MISSING_REQUEST_HEADER.equals(msgId))
        || !isFieldList(data, msgId)) {
      return List.of();
    }

    final var errors = new ArrayList<ProblemField>();

    for (final var name : Splitter.on(',').split(data)) {
      errors.add(new ProblemField(name, msgId, text));
    }

    return errors;
  }

  public static @NonNull List<ProblemField> fieldsOf(
      final @NonNull MethodArgumentNotValidException ex) {

    return ex.getBindingResult().getAllErrors().stream()
        .map(
            error ->
                new ProblemField(
                    error instanceof final FieldError fieldError
                        ? fieldError.getField()
                        : error.getObjectName(),
                    lastCode(error.getCodes(), error.getCode()),
                    error.getDefaultMessage()))
        .toList();
  }

  public static @NonNull List<ProblemField> fieldsOf(
      final @NonNull HandlerMethodValidationException ex) {

    final var fields = new ArrayList<ProblemField>();

    for (final var result : ex.getParameterValidationResults()) {
      final var parameter = result.getMethodParameter().getParameterName();

      if (result instanceof final ParameterErrors errors) {
        for (final var error : errors.getFieldErrors()) {
          fields.add(
              new ProblemField(
                  error.getField(),
                  lastCode(error.getCodes(), error.getCode()),
                  error.getDefaultMessage()));
        }
      } else if (result.getResolvableErrors().isEmpty()) {
        fields.add(new ProblemField(parameter, ProtocolErrorCodes.VALIDATION_ERROR, null));
      } else {
        for (final var error : result.getResolvableErrors()) {
          fields.add(
              new ProblemField(
                  parameter, lastCode(error.getCodes(), null), error.getDefaultMessage()));
        }
      }
    }

    return fields;
  }

  public static @NonNull List<ProblemField> fieldsOf(final @NonNull ValidationException ex) {

    if (!(ex instanceof final ConstraintViolationException violations)) {
      return List.of();
    }

    return violations.getConstraintViolations().stream()
        .map(
            violation -> {
              String field = "";

              for (final var node : violation.getPropertyPath()) {
                field = String.valueOf(node.getName());
              }

              return new ProblemField(
                  field,
                  violation
                      .getConstraintDescriptor()
                      .getAnnotation()
                      .annotationType()
                      .getSimpleName(),
                  violation.getMessage());
            })
        .toList();
  }

  /** The most general code Spring resolved for a constraint, i.e. its annotation name. */
  private static @NonNull String lastCode(
      final String @Nullable [] codes, final @Nullable String fallback) {

    if (codes != null && codes.length > 0) {
      return codes[codes.length - 1];
    }

    return fallback != null ? fallback : ProtocolErrorCodes.VALIDATION_ERROR;
  }

  /** A 503 that tells the client to repeat the request after {@code Retry-After} seconds. */
  public ResponseEntity<Object> retryLater(
      final @NonNull Object body, final @NonNull HttpServletRequest request) {

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(this.contentType(request))
        .header(HttpHeaders.RETRY_AFTER, LOCK_FAILURE_RETRY_AFTER)
        .body(body);
  }

  /**
   * The answer of an exception no handler names: 500, unless the response is gone or already
   * committed (then {@code null}, nothing is written).
   */
  public @Nullable ResponseEntity<Object> fallback(
      final @NonNull Throwable ex,
      final @NonNull HttpServletRequest request,
      final @Nullable HttpServletResponse response) {

    if (response == null) {
      log.debug("An exception occurred: {}", exceptionToString(ex, request));
      return null;
    }

    if (ex instanceof @NonNull final HttpClientErrorException exception) {
      // Neither the upstream body nor the exception message is logged: both can echo values of the
      // request (the message of this exception type carries the body).
      log.error(
          "Upstream call failed with HTTP {} ({}) while serving {} {}",
          exception.getStatusCode().value(),
          exception.getClass().getName(),
          request.getMethod(),
          request.getRequestURI());
    } else if (!NOT_LOGGED_EXCEPTIONS.contains(ex.getClass().getName())) {
      log.error(exceptionToString(ex, request));
    }

    if (response.isCommitted()) {
      log.warn(
          "Response already committed, skipping error body write for {}", ex.getClass().getName());
      return null;
    }

    response.resetBuffer();

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(this.contentType(request))
        .body(
            this.error(
                request, HttpStatus.INTERNAL_SERVER_ERROR, ProtocolErrorCodes.ERROR_OCCURRED));
  }
}
