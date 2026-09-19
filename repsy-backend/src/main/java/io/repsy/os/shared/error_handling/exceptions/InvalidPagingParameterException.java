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
package io.repsy.os.shared.error_handling.exceptions;

import java.io.Serial;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

/** Thrown when a paged request carries a {@code page} or {@code size} that is not acceptable. */
@Getter
public class InvalidPagingParameterException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Names of the offending request parameters, comma separated. */
  private final @NonNull String parameterNames;

  public InvalidPagingParameterException(final @NonNull String parameterNames) {
    super("Invalid paging parameter: " + parameterNames);
    this.parameterNames = parameterNames;
  }
}
