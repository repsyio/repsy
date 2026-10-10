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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-2062: pins what every protocol's auth pre-processor answers, so that folding the nine of them
 * into one template changes nothing a client can see. For each protocol it sends the same set of
 * requests (no credentials, a wrong Basic password, a forged Bearer token, an unknown scheme, a raw
 * token without a scheme, valid Basic credentials, a valid protocol token, an anonymous read and
 * write of a public repository, and the request after the failure throttle trips) and compares the
 * status, the {@code WWW-Authenticate} challenge, the content type and, for a refusal, the body
 * with the answers the separate pre-processors gave.
 *
 * <p>An accepted request is recorded by its status only: what the handler then answers (mostly a
 * 404, the package does not exist) is not the pre-processor's business. The repository name is
 * written as {@code {repo}} so that the table does not depend on the random names.
 */
@DisplayName("Auth pre-processors answer every credential case as they did (RPS-2062)")
class AuthPreProcessorMatrixIT extends AbstractIT {

  private static final int MAX_FAILURES = 20;
  private static final String API_KEY_HEADER = "X-NuGet-ApiKey";

  /** One protocol route family: a read of a repository and a write to one. */
  record Protocol(
      String label, RepoType type, String readPath, HttpMethod writeMethod, String writePath) {

    /**
     * Whether the write is sent here. The PyPI upload route only takes the multipart request a real
     * servlet container parses, which MockMvc does not produce; {@code PypiUploadRulesIT} pins its
     * anonymous and refused uploads over HTTP instead.
     */
    boolean writes() {
      return this.type != RepoType.PYPI;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  private static final List<String> LABELS =
      List.of(
          "MAVEN", "NPM", "CARGO", "NUGET", "PYPI", "RUBY", "GOLANG", "HELM", "HELM_OCI", "DOCKER");

  private static Stream<Protocol> protocols() {
    return Stream.of(
        new Protocol(
            "MAVEN",
            RepoType.MAVEN,
            "/{repo}/com/example/lib/1.0/lib-1.0.pom",
            HttpMethod.PUT,
            "/{repo}/com/example/lib/1.0/lib-1.0.pom"),
        new Protocol("NPM", RepoType.NPM, "/{repo}/some-package", HttpMethod.PUT, "/{repo}/pkg"),
        new Protocol(
            "CARGO",
            RepoType.CARGO,
            "/{repo}/de/pl/demo-crate",
            HttpMethod.PUT,
            "/{repo}/api/v1/crates/new"),
        new Protocol(
            "NUGET",
            RepoType.NUGET,
            "/{repo}/v3/package/demo/index.json",
            HttpMethod.PUT,
            "/{repo}/v3/package"),
        new Protocol("PYPI", RepoType.PYPI, "/{repo}/simple/demo/", HttpMethod.POST, "/{repo}/"),
        new Protocol(
            "RUBY", RepoType.RUBY, "/{repo}/names", HttpMethod.POST, "/{repo}/api/v1/gems"),
        new Protocol(
            "GOLANG",
            RepoType.GOLANG,
            "/{repo}/example.com/demo/@v/list",
            HttpMethod.PUT,
            "/{repo}/example.com/demo/@v/v1.0.0.zip"),
        new Protocol(
            "HELM", RepoType.HELM, "/{repo}/index.yaml", HttpMethod.POST, "/{repo}/api/charts"),
        new Protocol(
            "HELM_OCI",
            RepoType.HELM,
            "/v2/{repo}/payments/manifests/1.0.0",
            HttpMethod.POST,
            "/v2/{repo}/payments/blobs/uploads/"),
        new Protocol(
            "DOCKER",
            RepoType.DOCKER,
            "/v2/{repo}/app/manifests/latest",
            HttpMethod.POST,
            "/v2/{repo}/app/blobs/uploads/"));
  }

  /**
   * The answers of the nine pre-processors before RPS-2062, one line per protocol and case: {@code
   * <protocol> <case> => <status> | <challenges> | <content type> | <body>}. Run with {@code
   * -Dcharacterization.write=true} to rewrite it from the current answers (then review the diff).
   */
  private static final Path EXPECTED =
      Path.of("src", "test", "resources", "auth-pre-processor-characterization.txt");

  private static final Pattern ERROR_ID =
      Pattern.compile(
          "\"errorCode\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

  private static AbstractMockHttpServletRequestBuilder<?> write(
      final Protocol protocol, final String repoName) {
    return request(protocol.writeMethod(), protocol.writePath(), repoName);
  }

  private MockHttpServletResponse send(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static String outcome(final MockHttpServletResponse response, final String... repoNames)
      throws Exception {

    final var status = response.getStatus();
    final var challenges = String.join(" ;; ", response.getHeaders(WWW_AUTHENTICATE));
    final var refused = status == 401 || status == 403 || status == 429;
    final var retryAfter = response.getHeader(RETRY_AFTER);

    final var line =
        refused
            ? "%d | %s | %s | %s%s"
                .formatted(
                    status,
                    challenges,
                    Objects.toString(response.getHeader(CONTENT_TYPE), ""),
                    response.getContentAsString().replace("\n", "\\n"),
                    retryAfter == null ? "" : " | Retry-After")
            : "%d | %s".formatted(status, challenges);

    var normalized = ERROR_ID.matcher(line).replaceAll("\"errorCode\":\"<uuid>\"").strip();

    for (final var repoName : repoNames) {
      normalized = normalized.replace(repoName, "{repo}");
    }

    return normalized;
  }

  private static List<String> expected(final String label) throws IOException {
    return Files.readAllLines(EXPECTED, StandardCharsets.UTF_8).stream()
        .filter(line -> line.startsWith(label + " "))
        .toList();
  }

  private static synchronized void record(final String label, final List<String> actual)
      throws IOException {

    final var others =
        Files.exists(EXPECTED)
            ? Files.readAllLines(EXPECTED, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith(label + " "))
                .toList()
            : List.<String>of();

    final var lines = new ArrayList<>(others);
    lines.addAll(actual);
    lines.sort(
        Comparator.comparingInt(
            (String line) -> LABELS.indexOf(line.substring(0, line.indexOf(' ')))));
    Files.write(EXPECTED, lines, StandardCharsets.UTF_8);
  }

  private String dockerToken(final String repoName) throws Exception {
    final var response =
        this.send(
            post("/v2/token")
                .header(AUTHORIZATION, basicAuth(SEEDED_ADMIN_USERNAME, SEEDED_ADMIN_PASSWORD))
                .param("scope", "repository:%s/app:pull,push".formatted(repoName)));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("protocols")
  @DisplayName("answers each credential case as before")
  void matrix(final Protocol protocol) throws Exception {
    final var privateRepo =
        this.seedRepo(protocol.type(), uniqueRepoName("matrixpriv"), true, null).getName();
    final var publicRepo =
        this.seedRepo(protocol.type(), uniqueRepoName("matrixpub"), false, null).getName();
    final var read = protocol.readPath();
    final var validBearer = this.adminProtocolBearerToken();

    final var actual = new ArrayList<String>();
    final var cases =
        new Object[][] {
          {"anonymous", get(read, privateRepo)},
          {
            "badBasic",
            get(read, privateRepo)
                .header(AUTHORIZATION, basicAuth(SEEDED_ADMIN_USERNAME, "not-the-password"))
          },
          {"badBearer", get(read, privateRepo).header(AUTHORIZATION, "Bearer not.a.token")},
          {"unknownScheme", get(read, privateRepo).header(AUTHORIZATION, "Digest username=\"x\"")},
          {"rawToken", get(read, privateRepo).header(AUTHORIZATION, "not.a.token")},
          {
            "validBasic",
            get(read, privateRepo)
                .header(AUTHORIZATION, basicAuth(SEEDED_ADMIN_USERNAME, SEEDED_ADMIN_PASSWORD))
          },
          {"validBearer", get(read, privateRepo).header(AUTHORIZATION, validBearer)},
          {
            "validRawToken",
            get(read, privateRepo)
                .header(AUTHORIZATION, validBearer.substring(AuthUtils.AUTH_BEARER.length()))
          },
          {
            "apiKeyHeader",
            get(read, privateRepo)
                .header(API_KEY_HEADER, validBearer.substring(AuthUtils.AUTH_BEARER.length()))
          },
          {"badApiKeyHeader", get(read, privateRepo).header(API_KEY_HEADER, "not.a.token")},
          {"publicRead", get(read, publicRepo)},
          {"publicWrite", write(protocol, publicRepo)},
          {
            "publicWriteBadBearer",
            write(protocol, publicRepo).header(AUTHORIZATION, "Bearer not.a.token")
          },
        };

    for (final var testCase : cases) {
      if (!protocol.writes() && testCase[0].toString().startsWith("publicWrite")) {
        continue;
      }

      final var response = this.send((AbstractMockHttpServletRequestBuilder<?>) testCase[1]);
      actual.add(
          protocol.label()
              + " "
              + testCase[0]
              + " => "
              + outcome(response, privateRepo, publicRepo));
    }

    if (protocol.type() == RepoType.DOCKER) {
      final var response =
          this.send(get(read, privateRepo).header(AUTHORIZATION, this.dockerToken(privateRepo)));
      actual.add(protocol.label() + " registryToken => " + outcome(response, privateRepo));
    }

    final var client = "198.51.100." + (10 + Math.floorMod(protocol.label().hashCode(), 200));
    final var wrong = basicAuth(SEEDED_ADMIN_USERNAME, "throttled-guess");

    for (var i = 0; i < MAX_FAILURES; i++) {
      this.send(get(read, privateRepo).header(AUTHORIZATION, wrong).with(remoteAddr(client)));
    }

    final var throttled =
        this.send(get(read, privateRepo).header(AUTHORIZATION, wrong).with(remoteAddr(client)));
    actual.add(protocol.label() + " throttled => " + outcome(throttled, privateRepo));

    if (Boolean.getBoolean("characterization.write")) {
      record(protocol.label(), actual);
    }

    assertThat(actual).containsExactlyElementsOf(expected(protocol.label()));
  }

  @Test
  @DisplayName("Docker token flow: valid Basic credentials are refused with the Bearer challenge")
  void dockerRefusesBasicCredentials() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("matrixdocker"), true, null);

    final var response =
        this.send(
            get("/v2/{repo}/app/manifests/latest", repo.getName())
                .header(AUTHORIZATION, basicAuth(SEEDED_ADMIN_USERNAME, SEEDED_ADMIN_PASSWORD)));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Bearer realm=");
  }

  @Test
  @DisplayName("NuGet API key: a forged key is refused with an empty 401 and the Basic challenge")
  void nugetRefusesForgedApiKey() throws Exception {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("matrixnuget"), true, null);

    final var response =
        this.send(
            get("/{repo}/v3/package/demo/index.json", repo.getName())
                .header(API_KEY_HEADER, "not.a.token"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"Repsy\"");
    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  @DisplayName("NuGet API key: the header is not a credential for any other protocol")
  void apiKeyHeaderIsNuGetOnly() throws Exception {
    final var token = this.adminProtocolBearerToken().substring(AuthUtils.AUTH_BEARER.length());

    for (final var type :
        Arrays.stream(RepoType.values()).filter(type -> type != RepoType.NUGET).toList()) {
      final var protocol = protocols().filter(p -> p.type() == type).findFirst().orElseThrow();
      final var repo = this.seedRepo(type, uniqueRepoName("matrixkey"), true, null);

      final var response =
          this.send(get(protocol.readPath(), repo.getName()).header(API_KEY_HEADER, token));

      assertThat(response.getStatus()).as(type.name()).isEqualTo(401);
    }
  }
}
