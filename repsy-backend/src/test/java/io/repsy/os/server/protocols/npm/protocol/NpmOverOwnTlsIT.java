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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1559: Repsy's own HTTPS listener serves what the plain protocol port serves. It runs a real
 * Tomcat with the repo listener on TLS (a self-signed certificate made at test time) because
 * MockMvc has no connectors.
 *
 * <p>npm addresses a scoped package as {@code @scope%2Fname}. Tomcat refuses an encoded slash with
 * a bodyless 400 unless the connector says to decode it, and Spring Boot's own connector
 * customizers only reach the primary connector, so the TLS connector once did.
 *
 * <p>Runs without a test transaction (the server handles a request on its own thread) and deletes
 * the repo and the user it commits.
 */
@DisplayName("npm over Repsy's own HTTPS connector (RPS-1559)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class NpmOverOwnTlsIT extends AbstractIntegrationTest {

  private static final String STORE_PASSWORD = "changeit";
  private static final String KEY_ALIAS = "repsy";
  private static final String SCOPED_NAME = "@sc/pk";
  private static final String SCOPED_PATH = "@sc%2Fpk";

  private static final int PLAIN_PORT = freePort();
  private static final int API_PORT_OF_CONTEXT = freePort();
  private static final int TLS_PORT = freePort();
  private static final Path KEYSTORE = createKeystore();

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private NpmStorageService npmStorageService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ApplicationContext applicationContext;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @DynamicPropertySource
  static void registerListeners(final DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> PLAIN_PORT);
    registry.add("multiport.ports.api", () -> API_PORT_OF_CONTEXT);
    registry.add("multiport.port-aliases." + TLS_PORT, () -> "");
    registry.add("repsy.ssl.repo.enabled", () -> true);
    registry.add("repsy.ssl.repo.port", () -> TLS_PORT);
    registry.add("repsy.ssl.repo.key-store", KEYSTORE::toString);
    registry.add("repsy.ssl.repo.key-store-password", () -> STORE_PASSWORD);
    registry.add("repsy.ssl.repo.key-store-type", () -> "PKCS12");
    registry.add("repsy.ssl.repo.key-alias", () -> KEY_ALIAS);
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  @Test
  @DisplayName("reaches the application with an encoded slash, as the plain port does")
  void encodedSlashReachesTheApplication() throws Exception {

    final var request = "/nonexist/" + SCOPED_PATH;

    final var overTls = this.send("PUT", tls(request), "{}", Map.of());
    final var overHttp = this.send("PUT", plain(request), "{}", Map.of());

    // The application's own answer (a JSON envelope), not Tomcat's bodyless 400.
    assertThat(overTls.statusCode()).isNotEqualTo(400);
    assertThat(overTls.body()).isNotEmpty();
    assertThat(overTls.statusCode()).isEqualTo(overHttp.statusCode());
    // Same envelope, apart from its per-response errorCode.
    assertThat(new String(overTls.body(), StandardCharsets.UTF_8))
        .contains("\"msgId\":\"unknownPath\"");
    assertThat(new String(overHttp.body(), StandardCharsets.UTF_8))
        .contains("\"msgId\":\"unknownPath\"");
  }

  @Test
  @DisplayName("publishes and reads a scoped package, and compresses a large packument")
  void scopedPackageRoundTripWithCompression() throws Exception {

    final var repoName = uniqueRepoName("tls");
    final var repo = this.repoTxService.createRepo(repoName, RepoType.NPM, false, null);
    this.createdRepoIds.add(repo.getId());
    this.npmStorageService.createRepo(repo.getId());
    final var userInfo =
        this.userTxService.create(uniqueUsername("tls-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());
    final var token =
        this.protocolBearerTokenFor(this.userRepository.findById(userInfo.getId()).orElseThrow());

    final var body =
        NpmPublishBodies.body(
            this.objectMapper,
            repoName,
            SCOPED_NAME,
            "1.0.0",
            Map.of("description", "x".repeat(4_000)));
    final var published =
        this.send(
            "PUT",
            tls("/" + repoName + "/" + SCOPED_PATH),
            new String(body, StandardCharsets.UTF_8),
            Map.of(HttpHeaders.AUTHORIZATION, token, HttpHeaders.CONTENT_TYPE, "application/json"));
    assertThat(published.statusCode()).as("publish over TLS").isEqualTo(200);

    final var packument =
        this.send(
            "GET",
            tls("/" + repoName + "/" + SCOPED_PATH),
            null,
            Map.of(
                HttpHeaders.AUTHORIZATION,
                token,
                HttpHeaders.ACCEPT,
                "application/json",
                HttpHeaders.ACCEPT_ENCODING,
                "gzip"));

    assertThat(packument.statusCode()).isEqualTo(200);
    assertThat(packument.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).contains("gzip");
    try (final var gunzip = new GZIPInputStream(new ByteArrayInputStream(packument.body()))) {
      assertThat(new String(gunzip.readAllBytes(), StandardCharsets.UTF_8))
          .contains("\"name\":\"" + SCOPED_NAME + "\"");
    }
  }

  @Test
  @DisplayName("gives the HTTPS connector the connection timeout of the plain ones")
  void tlsConnectorHasTheConnectionTimeout() {

    final var plain = this.connectorOn(PLAIN_PORT);
    final var tls = this.connectorOn(TLS_PORT);

    assertThat(tls.getScheme()).isEqualTo("https");
    assertThat(((Http11NioProtocol) tls.getProtocolHandler()).getConnectionTimeout())
        .isEqualTo(((Http11NioProtocol) plain.getProtocolHandler()).getConnectionTimeout())
        .isEqualTo(120_000);
  }

  private Connector connectorOn(final int port) {
    final var server = ((WebServerApplicationContext) this.applicationContext).getWebServer();
    return List.of(((TomcatWebServer) server).getTomcat().getService().findConnectors()).stream()
        .filter(connector -> connector.getPort() == port)
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<byte[]> send(
      final String method, final URI uri, final String body, final Map<String, String> headers)
      throws Exception {

    final var builder =
        HttpRequest.newBuilder(uri)
            .timeout(java.time.Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(builder::header);

    try (final var client =
        HttpClient.newBuilder().sslContext(trustingKeystoreCertificate()).build()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private static URI tls(final String path) {
    return URI.create("https://localhost:" + TLS_PORT + path);
  }

  private static URI plain(final String path) {
    return URI.create("http://localhost:" + PLAIN_PORT + path);
  }

  /** An SSL context that trusts the self-signed certificate of the test keystore, and only that. */
  private static SSLContext trustingKeystoreCertificate() throws Exception {
    final var keyStore = KeyStore.getInstance("PKCS12");
    try (final var in = Files.newInputStream(KEYSTORE)) {
      keyStore.load(in, STORE_PASSWORD.toCharArray());
    }

    final var trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("repsy", keyStore.getCertificate(KEY_ALIAS));

    final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    final var context = SSLContext.getInstance("TLS");
    context.init(null, factory.getTrustManagers(), null);
    return context;
  }

  private static int freePort() {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A PKCS12 keystore with a self-signed certificate for localhost, made with the JDK keytool. */
  private static Path createKeystore() {
    try {
      final var directory = Files.createTempDirectory("repsy-tls-it");
      final var keystore = directory.resolve("keystore.p12");
      keystore.toFile().deleteOnExit();
      directory.toFile().deleteOnExit();

      final var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
      final var process =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  KEY_ALIAS,
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  keystore.toString(),
                  "-storepass",
                  STORE_PASSWORD)
              .redirectErrorStream(true)
              .start();
      final var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
      return keystore;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
