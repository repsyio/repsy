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
package io.repsy.os.shared.configs;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Spring Boot's {@link org.springframework.web.filter.FormContentFilter} that leaves the body of a
 * {@code PUT} or {@code PATCH} alone (RPS-1443).
 *
 * <p>The stock filter reads the body of a {@code PUT}, {@code PATCH} or {@code DELETE} into request
 * parameters whenever its {@code Content-Type} is {@code application/x-www-form-urlencoded}, and
 * that read consumes the stream. {@code curl -X PUT --data-binary @file} sends exactly that content
 * type unless told otherwise, so a Maven, Go, Cargo, Docker or Helm upload made that way used to
 * reach its handler as an empty body and was stored as a zero-byte file with a 200. On the wire
 * protocols a {@code PUT} or {@code PATCH} body is always the payload (an artifact, a blob chunk, a
 * manifest), whatever its content type says, and the panel API sends JSON, so neither needs the
 * parameters.
 *
 * <p>A {@code DELETE} still gets them: {@code gem yank} sends its gem name, version and platform as
 * a form-encoded body of a {@code DELETE}, and its handler reads them with {@code getParameter}.
 * Registering this filter makes Spring Boot's own one back off.
 */
@Component
public class PutBodyPreservingFormContentFilter extends OrderedFormContentFilter {

  @Override
  protected boolean shouldNotFilter(final @NonNull HttpServletRequest request) {

    final var method = request.getMethod();

    return HttpMethod.PUT.matches(method) || HttpMethod.PATCH.matches(method);
  }
}
