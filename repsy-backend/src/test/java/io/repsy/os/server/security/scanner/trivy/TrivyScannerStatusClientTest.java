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
package io.repsy.os.server.security.scanner.trivy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrivyScannerStatusClient")
class TrivyScannerStatusClientTest {

  private static final String API_KEY = "test-key";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (this.server != null) {
      this.server.stop(0);
    }
  }

  @Test
  @DisplayName("sends the api key and reads the status answer")
  void readsStatus() {
    final var apiKey = new AtomicReference<String>();
    final var scanId = UUID.randomUUID();

    this.startServer(
        exchange -> {
          apiKey.set(exchange.getRequestHeaders().getFirst("X-Scanner-Api-Key"));
          final var body = "{\"status\": \"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    final var response = this.client().fetchStatus(scanId);

    assertThat(response).isNotNull();
    assertThat(apiKey.get()).isEqualTo(API_KEY);
  }

  @Test
  @DisplayName("maps 404 to ScanJobNotFoundException")
  void notFoundIsScanJobNotFound() {
    this.startServer(
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    assertThatThrownBy(() -> this.client().fetchStatus(UUID.randomUUID()))
        .isInstanceOf(ScanJobNotFoundException.class);
  }

  @Test
  @DisplayName("maps another error status to TrivyScanException")
  void serverErrorIsScanException() {
    this.startServer(
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });

    assertThatThrownBy(() -> this.client().fetchStatus(UUID.randomUUID()))
        .isInstanceOf(TrivyScanException.class)
        .hasMessageContaining("Scanner status check failed")
        .hasMessageContaining("503");
  }

  private void startServer(final @NonNull HttpHandler handler) {
    try {
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (final IOException exception) {
      throw new IllegalStateException(exception);
    }

    this.server.createContext("/scan/", handler);
    this.server.start();
  }

  private @NonNull TrivyScannerStatusClient client() {
    final var properties =
        new TrivyScannerProperties(
            "http://127.0.0.1:" + this.server.getAddress().getPort(),
            API_KEY,
            5,
            3000,
            60,
            3,
            15,
            60);

    return new TrivyScannerStatusClient(
        properties, new TrivyScannerRestClientConfig().trivyScannerRestClient(properties));
  }
}
