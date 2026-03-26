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
package io.repsy.core.error_handling.utils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@UtilityClass
public class ErrorUtils {

  private static final @NonNull String ERROR_CODE_PREFIX = "Error Code: ";
  private static final @NonNull String STACK_TRACE_SEPARATOR =
      "Stack Trace ---------------------------------------\n";

  public static @NonNull String exceptionToString(
      final @NonNull Throwable ex, final @NonNull HttpServletRequest request) {

    return requestToString(request)
        + STACK_TRACE_SEPARATOR
        + ERROR_CODE_PREFIX
        + UUID.randomUUID()
        + "\n"
        + ExceptionUtils.getStackTrace(ex);
  }

  public static @NonNull String exceptionToString(
      final @NonNull ConversionFailedException ex, final @NonNull HttpServletRequest request) {

    return requestToString(request)
        + STACK_TRACE_SEPARATOR
        + ERROR_CODE_PREFIX
        + UUID.randomUUID()
        + "\n"
        + "Source Type: "
        + ex.getSourceType()
        + "\n"
        + "Target Type: "
        + ex.getTargetType()
        + "\n"
        + "Value: "
        + ex.getValue()
        + "\n"
        + ExceptionUtils.getStackTrace(ex);
  }

  public static @NonNull String exceptionToString(
      final @NonNull HttpRequestMethodNotSupportedException ex,
      final @NonNull HttpServletRequest request) {

    return requestToString(request)
        + STACK_TRACE_SEPARATOR
        + ERROR_CODE_PREFIX
        + UUID.randomUUID()
        + "\n"
        + "Method: "
        + ex.getMethod()
        + "\n"
        + "Supported HTTP Methods: "
        + ex.getSupportedHttpMethods()
        + "\n"
        + ExceptionUtils.getStackTrace(ex);
  }

  public static @NonNull String exceptionToString(
      final @NonNull MethodArgumentNotValidException ex,
      final @NonNull HttpServletRequest request) {

    final var errorMessage = new StringBuilder();

    errorMessage.append(requestToString(request));

    errorMessage.append(STACK_TRACE_SEPARATOR);

    final var bindingResult = (BeanPropertyBindingResult) ex.getBindingResult();

    final var errors = bindingResult.getAllErrors();

    for (final var error : errors) {
      errorMessage.append(error).append("\n");
    }

    errorMessage.append(ExceptionUtils.getStackTrace(ex));

    return errorMessage.toString();
  }

  public static @NonNull String exceptionToString(
      final @NonNull MissingServletRequestParameterException ex,
      final @NonNull HttpServletRequest request) {

    return requestToString(request)
        + STACK_TRACE_SEPARATOR
        + "Missing Parameter Name: "
        + ex.getParameterName()
        + "\n"
        + "Missing Parameter's Type: "
        + ex.getParameterType()
        + "\n"
        + ExceptionUtils.getStackTrace(ex);
  }

  private static @NonNull String requestToString(final @NonNull HttpServletRequest request) {

    final var errorMessage = new StringBuilder();

    errorMessage
        .append("\n")
        .append("Request -------------------------------------------\n")
        .append("Path: ")
        .append(request.getRequestURI())
        .append("\n")
        .append("Method: ")
        .append(request.getMethod())
        .append("\n");

    if (request.getQueryString() != null) {
      errorMessage
          .append("Query String: ")
          .append(request.getQueryString())
          .append("\n")
          .append("Parameters: ")
          .append(
              Collections.list(request.getParameterNames()).stream()
                  .map(pn -> pn + ":" + request.getParameter(pn))
                  .toList())
          .append("\n");
    }

    errorMessage.append("Headers -------------------------------------------\n");

    final var headerNames = request.getHeaderNames();

    while (headerNames.hasMoreElements()) {
      final var headerName = headerNames.nextElement();
      final var headerContent = request.getHeader(headerName);

      errorMessage.append(headerName).append(": ").append(headerContent).append("\n");
    }

    try {
      final var content =
          request
              .getReader()
              .lines()
              .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
              .toString();

      errorMessage.append("Content -------------------------------------------\n").append(content);
    } catch (final Exception _) {
      // pass
    }

    return errorMessage.toString();
  }
}
