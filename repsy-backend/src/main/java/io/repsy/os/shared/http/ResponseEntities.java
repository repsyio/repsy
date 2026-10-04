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
package io.repsy.os.shared.http;

import java.net.URI;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the success responses of the panel API (API guideline, Decision 5): the bare resource, 201
 * with a {@code Location} header on create, 204 with no body, and 202 with a {@code Location} of a
 * status resource for work that finishes later.
 */
@UtilityClass
@NullMarked
public final class ResponseEntities {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * A 200 response whose bare body is a single string, such as a token. Spring writes a {@code
   * String} as raw text, so the value is written as a JSON string literal here, which is what the
   * {@code application/json} contract and generated clients expect.
   *
   * @param value The string value
   * @return 200 with the JSON string literal as the body
   */
  public static ResponseEntity<String> jsonString(final String value) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(JSON.writeValueAsString(value));
  }

  /**
   * A 201 response whose body is the created resource.
   *
   * @param location The detail route of the created resource
   * @param body The created resource
   * @param <T> The type of the resource
   * @return 201 with the {@code Location} header and the body
   */
  public static <T> ResponseEntity<T> created(final URI location, final T body) {
    return ResponseEntity.status(HttpStatus.CREATED).location(location).body(body);
  }

  /**
   * A 204 response for a delete, or an update with nothing to return.
   *
   * @return 204 with no body
   */
  public static ResponseEntity<Void> noContent() {
    return ResponseEntity.noContent().build();
  }

  /**
   * A 202 response for work that is accepted and finishes later.
   *
   * @param statusLocation The status resource that reports the outcome
   * @return 202 with the {@code Location} header and no body
   */
  public static ResponseEntity<Void> accepted(final URI statusLocation) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).location(statusLocation).build();
  }
}
