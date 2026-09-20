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

import io.repsy.core.response.dtos.ResponseType;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.os.shared.error_handling.utils.OciErrors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Answers the errors of the Docker and Helm OCI endpoints ({@code /v2/}) with the error body of the
 * OCI distribution specification instead of the panel envelope (RPS-1039).
 *
 * <p>{@link ErrorHandler} keeps deciding the status and the headers of every failure and renders
 * the panel envelope. This advice only rewrites the body of such an error response when the request
 * is an OCI request, so the panel API and the other protocols are untouched and every exception the
 * OCI handlers throw is covered without a second set of exception handlers.
 */
@ControllerAdvice
@NullMarked
public class OciErrorBodyAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      final MethodParameter returnType,
      final Class<? extends HttpMessageConverter<?>> converterType) {

    return true;
  }

  @Override
  public @Nullable Object beforeBodyWrite(
      final @Nullable Object body,
      final MethodParameter returnType,
      final MediaType selectedContentType,
      final Class<? extends HttpMessageConverter<?>> selectedConverterType,
      final ServerHttpRequest request,
      final ServerHttpResponse response) {

    if (!(body instanceof final RestResponse<?> envelope)
        || envelope.getType() != ResponseType.ERROR
        || !(request instanceof final ServletServerHttpRequest servletRequest)
        || !(response instanceof final ServletServerHttpResponse servletResponse)
        || !OciErrors.isOciRequest(servletRequest.getServletRequest())) {
      return body;
    }

    return OciErrors.toOciError(
        envelope,
        servletResponse.getServletResponse().getStatus(),
        servletRequest.getServletRequest().getServletPath());
  }
}
