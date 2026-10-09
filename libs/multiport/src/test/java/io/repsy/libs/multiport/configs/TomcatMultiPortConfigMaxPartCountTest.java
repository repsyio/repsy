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
package io.repsy.libs.multiport.configs;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RPS-1657: {@code server.tomcat.max-part-count} (a protection limit, not something connector-
 * specific) must hold on the additional connector {@link TomcatMultiPortConfig} creates (the plain,
 * aliased port), exactly as it holds on the primary connector Spring Boot's own {@code
 * TomcatServletWebServerFactoryCustomizer} configures from the same property. Sends real multipart
 * requests to both, on a running embedded Tomcat, so the pin does not depend on how {@link
 * RepsyConnectorSettings#apply} happens to be implemented.
 */
class TomcatMultiPortConfigMaxPartCountTest {

  // Deliberately not 50 (Tomcat's own bare-connector default): if this test used 50 the
  // additional connector would happen to enforce the right limit even without the fix, since an
  // unconfigured Connector already defaults maxPartCount to 50.
  private static final int MAX_PART_COUNT = 7;
  private static final int TIMEOUT = 45_000;

  private WebServer server;

  @AfterEach
  void stopServer() {
    if (this.server != null) {
      this.server.stop();
    }
  }

  @Test
  void additionalConnectorRefusesTooManyPartsExactlyAsThePrimaryOneDoes() throws Exception {

    final var primaryPort = freePort();
    final var additionalPort = freePort();

    final var properties = new MultiPortProperties();
    properties.setMainPort(String.valueOf(primaryPort));
    properties.setPorts(Map.of("api", additionalPort));
    final var configuration = new TomcatMultiPortConfig(properties);
    ReflectionTestUtils.setField(configuration, "connectionTimeout", TIMEOUT);
    ReflectionTestUtils.setField(configuration, "maxPartCount", MAX_PART_COUNT);

    final var factory = new TomcatServletWebServerFactory(primaryPort);
    // Simulates what Spring Boot's own TomcatServletWebServerFactoryCustomizer already does to
    // the primary connector from server.tomcat.max-part-count: that customizer only runs inside
    // a full Spring Boot autoconfigured context, which this focused test does not start. The
    // additional connector below gets the equivalent setting through the production
    // repsyTomcatCustomizer() this test exercises, which is what RPS-1657 fixes.
    factory.addConnectorCustomizers(connector -> connector.setMaxPartCount(MAX_PART_COUNT));
    configuration.repsyTomcatCustomizer().customize(factory);

    this.server =
        factory.getWebServer(
            servletContext -> {
              final var registration =
                  servletContext.addServlet("probe", new MultipartProbeServlet());
              registration.addMapping("/*");
              registration.setMultipartConfig(new MultipartConfigElement(""));
            });
    this.server.start();

    assertThat(postMultipart(primaryPort, MAX_PART_COUNT))
        .as("primary connector, at the limit")
        .isEqualTo(200);
    assertThat(postMultipart(additionalPort, MAX_PART_COUNT))
        .as("additional connector, at the limit")
        .isEqualTo(200);

    assertThat(postMultipart(primaryPort, MAX_PART_COUNT + 1))
        .as("primary connector, over the limit")
        .isEqualTo(400);
    assertThat(postMultipart(additionalPort, MAX_PART_COUNT + 1))
        .as("additional connector, over the limit")
        .isEqualTo(400);
  }

  private static int postMultipart(final int port, final int partCount) throws Exception {

    final var boundary = "RepsyTestBoundary";
    final var body = new ByteArrayOutputStream();
    for (var i = 0; i < partCount; i++) {
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\n"
                  + "Content-Disposition: form-data; name=\"field"
                  + i
                  + "\"\r\n\r\n"
                  + "value"
                  + i
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

    final var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/probe"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();

    try (final var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }

  private static int freePort() {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Reads every part of the request: 200 when that succeeds, 400 when Tomcat refuses it. */
  private static final class MultipartProbeServlet extends HttpServlet {

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp)
        throws IOException {
      try {
        req.getParts();
        resp.setStatus(HttpServletResponse.SC_OK);
      } catch (final IOException | IllegalStateException | ServletException e) {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      }
    }
  }
}
