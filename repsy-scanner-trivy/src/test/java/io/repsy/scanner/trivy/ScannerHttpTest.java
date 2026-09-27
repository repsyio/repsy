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
package io.repsy.scanner.trivy;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.scanner.trivy.services.TrivyTestFilesAccess;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

// The scanner as one application on a random port, with a fake trivy script: the HTTP contract of
// POST /advisories and GET /status, the API key, and the schedule of the database refresh.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "scanner.security.api-key=test-key",
      "scanner.trivy.db-refresh-interval=PT6H",
      "scanner.advisories.max-body-bytes=2000",
      "scanner.advisories.max-wait-seconds=0"
    })
class ScannerHttpTest {

  private static final String KEY_HEADER = "X-Scanner-Api-Key";
  private static final String VALID_BODY =
      "{\"ecosystem\":\"npm\",\"packages\":[{\"name\":\"lodash\",\"version\":\"4.17.20\"}]}";

  private static final Path DIR = createDir();

  @LocalServerPort int port;

  @Autowired ScheduledAnnotationBeanPostProcessor scheduling;

  private final HttpClient client = HttpClient.newHttpClient();

  private static Path createDir() {
    try {
      final var dir = Files.createTempDirectory("scanner-http-test-");
      final var trivy = dir.resolve("trivy");
      Files.writeString(
          trivy,
          """
          #!/bin/sh
          case "$1" in
            version) echo '{"Version":"0.66.0"}' ;;
            sbom) echo '{"Results":[{"Target":"Node.js","Vulnerabilities":[{"VulnerabilityID":"CVE-2021-23337","PkgName":"lodash","InstalledVersion":"4.17.20","FixedVersion":"4.17.21","Status":"fixed","Severity":"HIGH"}]}]}' ;;
            *) exit 0 ;;
          esac
          """);
      Files.setPosixFilePermissions(trivy, PosixFilePermissions.fromString("rwxr-xr-x"));
      TrivyTestFilesAccess.writeDatabases(dir.resolve("cache"));
      return dir;
    } catch (final IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @DynamicPropertySource
  static void trivy(final DynamicPropertyRegistry registry) {
    registry.add("scanner.trivy.binary-path", () -> DIR.resolve("trivy").toString());
    registry.add("scanner.trivy.cache-dir", () -> DIR.resolve("cache").toString());
  }

  @AfterAll
  static void cleanUp() throws IOException {
    try (var paths = Files.walk(DIR)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  @BeforeEach
  void databaseIsPresent() throws IOException {
    TrivyTestFilesAccess.writeDatabases(DIR.resolve("cache"));
  }

  private HttpResponse<String> send(final HttpRequest.Builder request) throws Exception {
    return this.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder post(final String path, final String key, final String body) {
    final var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));

    if (key != null) {
      builder.header(KEY_HEADER, key);
    }

    return builder;
  }

  private HttpRequest.Builder get(final String path, final String key) {
    final var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));

    if (key != null) {
      builder.header(KEY_HEADER, key);
    }

    return builder;
  }

  @Test
  void healthNeedsNoKeyAndReportsNothingElse() throws Exception {
    final var response = this.send(this.get("/health", null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
  }

  @Test
  void everyEndpointButHealthRefusesARequestWithoutTheKeyOrWithTheWrongOne() throws Exception {
    final var scan = "/scan";
    final var poll = "/scan/" + UUID.randomUUID();

    assertThat(this.send(this.post(scan, null, "{}")).statusCode()).isEqualTo(401);
    assertThat(this.send(this.post(scan, "wrong", "{}")).statusCode()).isEqualTo(401);
    assertThat(this.send(this.get(poll, null)).statusCode()).isEqualTo(401);
    assertThat(this.send(this.get(poll, "wrong")).statusCode()).isEqualTo(401);
    assertThat(this.send(this.get("/actuator/info", null)).statusCode()).isEqualTo(401);
  }

  @Test
  void aBlankOrPlaceholderKeyIsNotTheKey() throws Exception {
    for (final var key : new String[] {"", " ", "${SCANNER_API_KEY}", "${SCANNER_API_KEY:}"}) {
      assertThat(this.send(this.get("/status", key)).statusCode()).isEqualTo(401);
      assertThat(this.send(this.post("/advisories", key, VALID_BODY)).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void theKeyOpensTheScanEndpoints() throws Exception {
    // An unknown scan id: past the filter (404), not refused (401).
    assertThat(this.send(this.get("/scan/" + UUID.randomUUID(), "test-key")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void statusNeedsTheApiKey() throws Exception {
    assertThat(this.send(this.get("/status", null)).statusCode()).isEqualTo(401);
    assertThat(this.send(this.get("/status", "wrong")).statusCode()).isEqualTo(401);
  }

  @Test
  void statusReportsTheTrivyVersionAndTheDatabaseDates() throws Exception {
    final var response = this.send(this.get("/status", "test-key"));

    assertThat(response.statusCode()).isEqualTo(200);
    final var json = JsonMapper.builder().build().readTree(response.body());
    assertThat(json.get("trivyVersion").asString()).isEqualTo("0.66.0");
    assertThat(json.get("dbUpdatedAt").asString()).isEqualTo("2026-09-26T19:03:57.371914884Z");
    assertThat(json.get("dbDownloadedAt").asString()).isEqualTo("2026-09-26T19:03:57.371914884Z");
    assertThat(json.get("javaDbUpdatedAt").asString()).isEqualTo("2026-09-26T19:03:57.371914884Z");
  }

  @Test
  void statusReportsNullDatesWithoutDatabases() throws Exception {
    Files.delete(DIR.resolve("cache/db/trivy.db"));
    Files.delete(DIR.resolve("cache/java-db/metadata.json"));

    final var json =
        JsonMapper.builder().build().readTree(this.send(this.get("/status", "test-key")).body());

    assertThat(json.get("trivyVersion").asString()).isEqualTo("0.66.0");
    assertThat(json.get("dbUpdatedAt").isNull()).isTrue();
    assertThat(json.get("javaDbUpdatedAt").isNull()).isTrue();
  }

  @Test
  void advisoriesNeedTheApiKey() throws Exception {
    assertThat(this.send(this.post("/advisories", null, VALID_BODY)).statusCode()).isEqualTo(401);
    assertThat(this.send(this.post("/advisories", "wrong", VALID_BODY)).statusCode())
        .isEqualTo(401);
  }

  @Test
  void advisoriesAnswerWithTheFindingsAndTheDatabaseDate() throws Exception {
    final var response = this.send(this.post("/advisories", "test-key", VALID_BODY));

    assertThat(response.statusCode()).isEqualTo(200);
    final var json = JsonMapper.builder().build().readTree(response.body());
    assertThat(json.get("dbUpdatedAt").asString()).isEqualTo("2026-09-26T19:03:57.371914884Z");
    assertThat(json.get("scannerVersion").asString()).isEqualTo("0.66.0");
    assertThat(json.get("findings")).hasSize(1);
    final var finding = json.get("findings").get(0);
    assertThat(finding.get("cveId").asString()).isEqualTo("CVE-2021-23337");
    assertThat(finding.get("packageName").asString()).isEqualTo("lodash");
    assertThat(finding.get("packageVersion").asString()).isEqualTo("4.17.20");
    assertThat(finding.get("fixedVersion").asString()).isEqualTo("4.17.21");
    assertThat(finding.get("severity").asString()).isEqualTo("HIGH");
    assertThat(finding.get("fixStatus").asString()).isEqualTo("FIXED");
  }

  @Test
  void advisoriesRefuseABadRequestWith400() throws Exception {
    for (final var body :
        new String[] {
          "{\"ecosystem\":\"maven\",\"packages\":[]}",
          "{\"ecosystem\":\"npm\"}",
          "{\"ecosystem\":\"npm\",\"packages\":[{\"name\":\"\",\"version\":\"1\"}]}",
          "not json",
          "null"
        }) {
      final var response = this.send(this.post("/advisories", "test-key", body));

      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(response.body()).as(body).contains("\"message\"");
    }
  }

  @Test
  void advisoriesRefuseABodyOverTheLimitWith413() throws Exception {
    final var big = VALID_BODY + " ".repeat(2001);

    assertThat(this.send(this.post("/advisories", "test-key", big)).statusCode()).isEqualTo(413);
  }

  @Test
  void advisoriesRefuseAChunkedBodyOverTheLimitWith413() throws Exception {
    // no Content-Length to check up front: the bound applies while the body is read
    final var big =
        (VALID_BODY + " ".repeat(2001)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/advisories"))
            .header(KEY_HEADER, "test-key")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(big)));

    assertThat(this.send(request).statusCode()).isEqualTo(413);
  }

  @Test
  void advisoriesRefuseAnotherContentTypeWith415() throws Exception {
    final var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/advisories"))
            .header(KEY_HEADER, "test-key")
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(VALID_BODY));

    assertThat(this.send(request).statusCode()).isEqualTo(415);
  }

  @Test
  void advisoriesAnswer503WithoutADatabase() throws Exception {
    Files.delete(DIR.resolve("cache/db/metadata.json"));

    final var response = this.send(this.post("/advisories", "test-key", VALID_BODY));

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("not been downloaded");
  }

  @Test
  void theDatabaseRefreshIsScheduledEveryConfiguredIntervalAndFirstAfterOne() {
    final var refresh =
        this.scheduling.getScheduledTasks().stream()
            .map(task -> task.getTask())
            .filter(
                task -> task.getRunnable().toString().contains("TrivyDatabaseRefreshTask.refresh"))
            .toList();

    assertThat(refresh).hasSize(1);
    final var task = (FixedDelayTask) refresh.getFirst();
    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(6));
    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(6));
  }
}
