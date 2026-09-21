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
package io.repsy.protocols.shared.exceptions;

import java.io.Serial;
import lombok.Getter;

/**
 * Thrown when a client has made too many failed password checks and is refused until its window
 * ends. It carries no username and no credential, so it can be logged and answered as it is.
 *
 * <p>It sits in the shared protocol module because a protocol handler that turns every failure of
 * its login into 401 has to let this one through, or the client is told to log in again instead of
 * waiting.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Seconds until the client may try again. */
  private final long retryAfterSeconds;

  public TooManyRequestsException(final long retryAfterSeconds) {
    super("Too many failed authentication attempts, retry after " + retryAfterSeconds + "s");
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
