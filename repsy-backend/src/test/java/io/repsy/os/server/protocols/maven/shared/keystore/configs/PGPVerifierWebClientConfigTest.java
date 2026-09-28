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
package io.repsy.os.server.protocols.maven.shared.keystore.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * RPS-1469: a key-server lookup ({@link
 * io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService}) runs for a key
 * that is not registered, so a slow or unreachable key server must not be able to hang a lookup
 * forever. A real, local HTTP server plays a slow or unreachable key server; the timeouts are the
 * ones {@link PGPVerifierWebClientConfig} actually configures, not a copy of them, so a change of
 * the configured values is caught here too.
 */
@DisplayName("PGPVerifierWebClientConfig timeouts (RPS-1469)")
class PGPVerifierWebClientConfigTest {

  // Mirrors PGPVerifierWebClientConfig's own constants: kept a little above them so a bound that is
  // exactly right does not make the test flaky, and well below what an unbounded wait would take.
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration GENEROUS_UPPER_BOUND = Duration.ofSeconds(9);

  private final CountDownLatch releaseServer = new CountDownLatch(1);
  private final ExecutorService serverThreads = Executors.newCachedThreadPool();

  private HttpServer server;

  @AfterEach
  void stopServer() {
    this.releaseServer.countDown();

    if (this.server != null) {
      this.server.stop(0);
    }

    this.serverThreads.shutdownNow();
  }

  @Test
  @DisplayName("a fast key server answers normally")
  void fastServerAnswers() {
    this.startServer(
        exchange -> {
          final var body = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nnot a real key\n".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });

    final var answer =
        new PGPVerifierWebClientConfig()
            .pgpVerifierWebClient()
            .get()
            .uri(this.baseUrl() + "/pks/lookup")
            .retrieve()
            .bodyToMono(String.class)
            .block();

    assertThat(answer).contains("BEGIN PGP PUBLIC KEY BLOCK");
  }

  @Test
  @DisplayName(
      "a key server that reads the request but never answers is bounded by the response"
          + " timeout, not left to hang")
  void unresponsiveServerTimesOutBounded() {
    this.startServer(
        exchange -> {
          // Never write a response: the client waits for headers that never come.
          this.await();
          exchange.close();
        });

    final var start = System.nanoTime();

    assertThatThrownBy(
            () ->
                new PGPVerifierWebClientConfig()
                    .pgpVerifierWebClient()
                    .get()
                    .uri(this.baseUrl() + "/pks/lookup")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block())
        .isInstanceOfAny(WebClientResponseException.class, WebClientRequestException.class);

    final var elapsed = Duration.ofNanos(System.nanoTime() - start);
    assertThat(elapsed)
        .as("bounded by the response timeout, not left to hang")
        .isGreaterThanOrEqualTo(RESPONSE_TIMEOUT.minusSeconds(1))
        .isLessThan(GENEROUS_UPPER_BOUND);
  }

  @Test
  @DisplayName("an unreachable key server fails fast, bounded by the connect timeout")
  void unreachableServerFailsFastBounded() throws IOException {
    final int closedPort;

    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }

    final var start = System.nanoTime();

    assertThatThrownBy(
            () ->
                new PGPVerifierWebClientConfig()
                    .pgpVerifierWebClient()
                    .get()
                    .uri("http://127.0.0.1:" + closedPort + "/pks/lookup")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block())
        .isInstanceOf(WebClientRequestException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("a refused connection must not wait for the response timeout")
        .isLessThan(GENEROUS_UPPER_BOUND);
  }

  private void await() {
    try {
      this.releaseServer.await();
    } catch (final InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private void startServer(final com.sun.net.httpserver.HttpHandler handler) {
    try {
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (final IOException exception) {
      throw new IllegalStateException(exception);
    }

    this.server.createContext("/pks/lookup", handler);
    this.server.setExecutor(this.serverThreads);
    this.server.start();
  }

  private @NonNull String baseUrl() {
    return "http://127.0.0.1:" + this.server.getAddress().getPort();
  }
}
