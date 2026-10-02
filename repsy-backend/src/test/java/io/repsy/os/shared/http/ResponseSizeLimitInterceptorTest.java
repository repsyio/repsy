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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@DisplayName("ResponseSizeLimitInterceptor")
class ResponseSizeLimitInterceptorTest {

  private static final int LIMIT = 1024;

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (this.server != null) {
      this.server.stop(0);
    }
  }

  @Test
  @DisplayName("lets a body within the limit through")
  void withinLimit() {
    this.serve(LIMIT, true);

    assertThat(this.get()).hasSize(LIMIT);
  }

  @Test
  @DisplayName("refuses a body whose Content-Length is above the limit")
  void announcedAboveLimit() {
    this.serve(LIMIT + 1, true);

    assertThatThrownBy(this::get).isInstanceOf(RestClientException.class);
  }

  @Test
  @DisplayName("refuses a chunked body that grows above the limit")
  void streamedAboveLimit() {
    this.serve(LIMIT + 1, false);

    assertThatThrownBy(this::get).isInstanceOf(RestClientException.class);
  }

  private String get() {
    return RestClient.builder()
        .requestInterceptor(new ResponseSizeLimitInterceptor(LIMIT))
        .build()
        .get()
        .uri("http://127.0.0.1:" + this.server.getAddress().getPort() + "/")
        .retrieve()
        .body(String.class);
  }

  private void serve(final int size, final boolean announceLength) {
    final var body = new byte[size];
    Arrays.fill(body, (byte) 'a');

    this.start(
        exchange -> {
          exchange.sendResponseHeaders(200, announceLength ? body.length : 0);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  private void start(final @NonNull HttpHandler handler) {
    try {
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (final IOException exception) {
      throw new IllegalStateException(exception);
    }

    this.server.createContext("/", handler);
    this.server.start();
  }
}
