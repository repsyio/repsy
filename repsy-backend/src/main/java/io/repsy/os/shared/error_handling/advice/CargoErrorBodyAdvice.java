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

import io.repsy.core.response.dtos.ResponseType;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Answers the failures of a Cargo web API request that its handler left to {@link
 * ProtocolErrorAdvice} (a storage outage, a database failure) with Cargo's error body, {@code
 * {"errors":[{"detail":"errorOccurred"}]}}, instead of the RestResponse envelope (RPS-2104). Cargo
 * prints {@code errors[].detail} and nothing else, so the envelope reached the user as an
 * unreadable response.
 *
 * <p>Like {@link OciErrorBodyAdvice}, {@link ProtocolErrorAdvice} keeps deciding the status and the
 * headers ({@code Retry-After} on a 503); this advice only rewrites the body, and only on a request
 * the Cargo handler marked with {@link CargoConstants#ERROR_BODY_ATTRIBUTE}. The detail is the
 * message id, the stable code the envelope carried.
 */
@ControllerAdvice
@NullMarked
public class CargoErrorBodyAdvice implements ResponseBodyAdvice<Object> {

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
        || servletRequest.getServletRequest().getAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE)
            == null) {
      return body;
    }

    return ProtocolErrorBody.withDetail(envelope.getMsgId());
  }
}
