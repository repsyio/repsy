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
package io.repsy.libs.protocol.router;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

public interface ProtocolMethodHandler {

  @NonNull List<@NonNull HttpMethod> getSupportedMethods();

  @NonNull Map<@NonNull String, @NonNull Object> getProperties();

  @NonNull PathParser getPathParser();

  @NonNull ResponseEntity<@NonNull Object> handle(
      @NonNull ProtocolContext parsedPath,
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response)
      throws Exception;
}
