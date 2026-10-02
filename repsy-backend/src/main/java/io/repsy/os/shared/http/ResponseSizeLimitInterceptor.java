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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Caps the size of a response body a {@code RestClient} reads into memory. A {@code WebClient} had
 * this built in ({@code maxInMemorySize}); a {@code RestClient} reads whatever the server sends, so
 * an upstream that answers with a huge body would otherwise be buffered whole. A body above the
 * limit fails the call with an {@link IOException} (surfaced as a {@code ResourceAccessException}),
 * whether it announces its size in {@code Content-Length} or only exceeds it while streaming.
 */
public final class ResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {

  private final long maxBytes;

  public ResponseSizeLimitInterceptor(final long maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  public @NonNull ClientHttpResponse intercept(
      final @NonNull HttpRequest request,
      final byte @NonNull [] body,
      final @NonNull ClientHttpRequestExecution execution)
      throws IOException {

    final var response = execution.execute(request, body);

    try {
      final var announced = response.getHeaders().getContentLength();

      if (announced > this.maxBytes) {
        throw new IOException(
            "Response body of " + announced + " bytes exceeds the limit of " + this.maxBytes);
      }
    } catch (final IOException exception) {
      response.close();
      throw exception;
    }

    return new LimitedResponse(response, this.maxBytes);
  }

  private record LimitedResponse(@NonNull ClientHttpResponse delegate, long maxBytes)
      implements ClientHttpResponse {

    @Override
    public @NonNull HttpStatusCode getStatusCode() throws IOException {
      return this.delegate.getStatusCode();
    }

    @Override
    public @NonNull String getStatusText() throws IOException {
      return this.delegate.getStatusText();
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
      return this.delegate.getHeaders();
    }

    @Override
    public @NonNull InputStream getBody() throws IOException {
      return new LimitedStream(this.delegate.getBody(), this.maxBytes);
    }

    @Override
    public void close() {
      this.delegate.close();
    }
  }

  private static final class LimitedStream extends FilterInputStream {

    private final long maxBytes;
    private long read;

    LimitedStream(final @NonNull InputStream in, final long maxBytes) {
      super(in);
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      final var value = super.read();

      if (value >= 0) {
        this.count(1);
      }

      return value;
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length)
        throws IOException {
      final var count = super.read(buffer, offset, length);

      if (count > 0) {
        this.count(count);
      }

      return count;
    }

    private void count(final int bytes) throws IOException {
      this.read += bytes;

      if (this.read > this.maxBytes) {
        throw new IOException("Response body exceeds the limit of " + this.maxBytes + " bytes");
      }
    }
  }
}
