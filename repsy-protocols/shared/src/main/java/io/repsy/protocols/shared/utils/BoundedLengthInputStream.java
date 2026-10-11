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

/**
 * Reads at most {@code maxBytes} from {@code delegate}, then reports end-of-stream whatever the
 * delegate still has left. Wraps the request body before it is spooled (RPS-1119), so a {@code
 * .crate} whose declared length is shorter than what the client actually sends never pulls the
 * extra bytes into the spool; a body that ends early is instead reported by the spooled size coming
 * out shorter than the declared length. The delegate is not closed here: it is the protocol's own
 * request stream, which the caller owns.
 */
public final class BoundedLengthInputStream extends InputStream {

  private final InputStream delegate;
  private long remaining;

  public BoundedLengthInputStream(final InputStream delegate, final long maxBytes) {
    this.delegate = delegate;
    this.remaining = maxBytes;
  }

  @Override
  public int read() throws IOException {
    if (this.remaining <= 0) {
      return -1;
    }

    final var b = this.delegate.read();
    if (b >= 0) {
      this.remaining--;
    }

    return b;
  }

  @Override
  public int read(final byte[] b, final int off, final int len) throws IOException {
    if (this.remaining <= 0) {
      return -1;
    }

    final var toRead = (int) Math.min(len, this.remaining);
    final var read = this.delegate.read(b, off, toRead);

    if (read > 0) {
      this.remaining -= read;
    }

    return read;
  }
}
