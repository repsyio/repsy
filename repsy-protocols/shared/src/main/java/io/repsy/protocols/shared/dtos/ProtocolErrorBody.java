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
package io.repsy.protocols.shared.dtos;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The error body of the formats whose clients print {@code errors[]} (RPS-2060): one record for the
 * shape {@code {"errors":[{"<field>":"<text>"}]}}, where the field is the one the format's client
 * reads. Cargo reads {@code detail} and NuGet {@code message}, so each factory writes exactly one
 * of them and the wire bodies are the ones the two former twin records produced. A missing text is
 * written as {@code null}, as the records did.
 */
@NullMarked
public record ProtocolErrorBody(List<Map<String, @Nullable String>> errors) {

  private static final String DETAIL = "detail";
  private static final String MESSAGE = "message";

  /** Cargo's shape: {@code {"errors":[{"detail":"..."}]}}. */
  public static ProtocolErrorBody withDetail(final @Nullable String detail) {
    return new ProtocolErrorBody(List.of(Collections.singletonMap(DETAIL, detail)));
  }

  /** NuGet's shape: {@code {"errors":[{"message":"..."}]}}. */
  public static ProtocolErrorBody withMessage(final @Nullable String message) {
    return new ProtocolErrorBody(List.of(Collections.singletonMap(MESSAGE, message)));
  }
}
