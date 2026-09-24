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
package io.repsy.protocols.npm.shared.audit;

import java.io.Serial;
import org.jspecify.annotations.NullMarked;

/** Thrown when the body of an audit request cannot be used, and answered with 400. */
@NullMarked
public class InvalidAuditRequestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidAuditRequestException(final String message) {
    super(message);
  }

  public InvalidAuditRequestException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
