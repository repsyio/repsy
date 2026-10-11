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
package io.repsy.protocols.shared.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Optional;

/** What the upload handlers of the wire protocols share about the body they are sent (RPS-1466). */
public final class RequestBodies {

  private RequestBodies() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * The body with its first byte put back, or empty when it has no byte in it. It has none when it
   * was declared empty ({@code Content-Length: 0}), sent chunked with no chunk, or already consumed
   * by something else on the way. The caller decides whether an empty body is an error: it is for
   * an artifact, a manifest or a publish request, and it is not for a blob, whose empty content is
   * a valid one.
   */
  public static Optional<InputStream> nonEmpty(final InputStream body) throws IOException {

    final var pushback = new PushbackInputStream(body, 1);
    final var first = pushback.read();

    if (first < 0) {
      return Optional.empty();
    }

    pushback.unread(first);

    return Optional.of(pushback);
  }
}
