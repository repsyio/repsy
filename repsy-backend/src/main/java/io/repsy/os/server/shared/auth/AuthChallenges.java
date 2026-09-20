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
package io.repsy.os.server.shared.auth;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;

/**
 * Attaches the {@code WWW-Authenticate} challenge of a protocol to a 401 that rejected the
 * credentials of a request. RFC 9110, section 15.5.2 requires every 401 to carry one, and some
 * clients only prompt for credentials again when they see it. The pre-processors already send the
 * challenge when a request carries no credentials at all; this covers the credentials they refuse.
 */
@UtilityClass
@NullMarked
public final class AuthChallenges {

  /**
   * Returns the exception to throw for a rejected credential: the same message, with the challenge
   * in its headers so that {@code ErrorHandler} writes it to the 401. An exception that already
   * carries a challenge of its own is returned as it is.
   *
   * @param exception The authentication failure
   * @param challenge The {@code WWW-Authenticate} header value of the protocol
   * @return The failure to throw
   */
  public static UnAuthorizedException challenged(
      final UnAuthorizedException exception, final String challenge) {

    final var existing = exception.getHeaders();

    if (existing != null && hasChallenge(existing)) {
      return exception;
    }

    final var headers = new HashMap<String, String>();

    if (existing != null) {
      headers.putAll(existing);
    }

    headers.put(HttpHeaders.WWW_AUTHENTICATE, challenge);

    final var challenged = new UnAuthorizedException(exception.getMessage(), Map.copyOf(headers));

    challenged.initCause(exception);

    return challenged;
  }

  private static boolean hasChallenge(final Map<String, String> headers) {

    return headers.keySet().stream().anyMatch(HttpHeaders.WWW_AUTHENTICATE::equalsIgnoreCase);
  }
}
