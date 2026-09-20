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
package io.repsy.os.shared.error_handling.dtos;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The error body of the OCI distribution specification: {@code {"errors": [{"code", "message",
 * "detail"}]}}. Registry clients read {@code errors[].code} and show {@code errors[].message}.
 *
 * @param errors The errors the request failed with; the registry reports one
 */
@NullMarked
public record OciErrorResponse(List<Error> errors) {

  /**
   * One error of the body.
   *
   * @param code One of the {@link OciErrorCode} names
   * @param message A human readable description
   * @param detail Machine readable context of the error, when there is any
   */
  public record Error(String code, String message, @Nullable Object detail) {}

  /**
   * Builds a body with a single error.
   *
   * @param code Error code
   * @param message Human readable description
   * @param detail Context of the error, may be null
   * @return The error body
   */
  public static OciErrorResponse of(
      final OciErrorCode code, final String message, final @Nullable Object detail) {

    return new OciErrorResponse(List.of(new Error(code.name(), message, detail)));
  }
}
