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
package io.repsy.os.server.protocols.pypi.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * RPS-2092: the rules a twine upload ({@code POST /{repo}/}, multipart with {@code :action}, {@code
 * name}, {@code version}, {@code sha256_digest} and a {@code content} file) is held to before
 * anything is stored, asserted by status next to the {@code msgId} of the protocol envelope.
 *
 * <p>What each case answers today (the intended behaviour, pinned as it is):
 *
 * <ul>
 *   <li>no credentials: 401 {@code unAuthorized}, on a public repo too, because an upload needs the
 *       WRITE permission;
 *   <li>a plain USER on a public repo: 200, a USER may write (OS has no per-repo write rule), so
 *       there is no 403 case for an upload;
 *   <li>a second upload of an existing file with {@code allowOverride} off: <b>403</b> {@code
 *       fileAlreadyExists} (the ticket expected 409, but the {@code AccessNotAllowedException}
 *       behind it maps to 403 in {@code ProtocolErrorAdvice}); the stored bytes stay the first
 *       ones;
 *   <li>a file part over {@code MULTIPART_MAX_FILE_SIZE}: 413 {@code payloadTooLarge}.
 * </ul>
 *
 * <p>Runs through a real Tomcat on two ports found free at startup, like the {@code
 * AbstractMultipartLimitIT} suites, because the multipart limit is enforced by the servlet
 * container and {@code MockMvc} never applies it. The limit is set low so the oversize body stays
 * small. This class therefore owns one Spring context (properties, ports and a mock bean of its
 * own; {@code ContextCountGuard} counts it). It runs without a test transaction (the server thread
 * cannot see uncommitted rows) and deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(
    properties = {
      "MULTIPART_MAX_FILE_SIZE=64KB",
      "MULTIPART_MAX_REQUEST_SIZE=100KB",
    })
@DisplayName("PyPI upload rules (RPS-2092)")
class PypiUploadRulesIT extends AbstractIT {

  private static final int PROTOCOL_PORT = freePort();
  private static final int API_PORT = freePort();
  private static final String WHEEL = "rules_pkg-1.0.0-py3-none-any.whl";

  @DynamicPropertySource
  static void registerPortsOfThisContext(final DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> PROTOCOL_PORT);
    registry.add("multiport.ports.api", () -> API_PORT);
  }

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiStorageService pypiStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** The status and the body of a response, whatever the status. */
  private record Answer(int status, String body) {}

  private static int freePort() {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo publicPypiRepo() {
    final var created =
        this.repoTxService.createRepo(uniqueRepoName("pypi-rules"), RepoType.PYPI, false, null);
    this.createdRepoIds.add(created.getId());
    this.pypiStorageService.createRepo(created.getStorageKey());

    return this.repoRepository.findById(created.getId()).orElseThrow();
  }

  private String tokenOf(final UserRole role) {
    final var info =
        this.userTxService.create(uniqueUsername("pypi-rules"), role, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());

    return this.protocolBearerTokenFor(this.userRepository.findById(info.getId()).orElseThrow());
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** A twine upload of {@code content}; {@code token} null sends no credentials. */
  private Answer upload(final Repo repo, final String token, final byte[] content)
      throws Exception {
    final var form = new LinkedMultiValueMap<String, Object>();
    form.add(":action", "file_upload");
    form.add("name", "rules-pkg");
    form.add("version", "1.0.0");
    form.add("requires_python", ">=3.9");
    form.add("sha256_digest", sha256Hex(content));
    form.add(
        "content",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return WHEEL;
          }
        });

    return RestClient.create()
        .post()
        .uri("http://localhost:%d/%s/".formatted(PROTOCOL_PORT, repo.getName()))
        .headers(
            headers -> {
              if (token != null) {
                headers.set(HttpHeaders.AUTHORIZATION, token);
              }
            })
        .body(form)
        .exchange(
            (request, response) ->
                new Answer(
                    response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private int releaseCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from pypi_release r join pypi_package p on p.id = r.package_id
        where p.repo_id = ?""",
        Integer.class,
        repo.getId());
  }

  private static byte[] bytes(final String marker) {
    return marker.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("an anonymous upload to a public repo is a 401 unAuthorized and stores nothing")
  void anonymousUploadIsUnauthorized() throws Exception {
    final var repo = this.publicPypiRepo();

    final var answer = this.upload(repo, null, bytes("anonymous"));

    assertThat(answer.status()).isEqualTo(401);
    assertThat(answer.body()).contains("\"msgId\":\"unAuthorized\"");
    assertThat(this.releaseCount(repo)).isZero();
    assertThat(storageDirOf(repo)).isEmptyDirectory();
  }

  @Test
  @DisplayName("a plain USER may upload to a public repo (200): OS has no per-repo write rule")
  void userMayUploadToAPublicRepo() throws Exception {
    final var repo = this.publicPypiRepo();

    final var answer = this.upload(repo, this.tokenOf(UserRole.USER), bytes("user"));

    assertThat(answer.status()).isEqualTo(200);
    assertThat(this.releaseCount(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "an ADMIN may upload; the same file again with override off is a 403 fileAlreadyExists")
  void duplicateWithOverrideOffIsRefused() throws Exception {
    final var repo = this.publicPypiRepo();
    this.jdbcTemplate.update("update repo set allow_override = false where id = ?", repo.getId());
    final var admin = this.tokenOf(UserRole.ADMIN);

    final var first = this.upload(repo, admin, bytes("original"));
    assertThat(first.status()).isEqualTo(200);

    final var second = this.upload(repo, admin, bytes("replacement"));

    assertThat(second.status()).isEqualTo(403);
    assertThat(second.body()).contains("\"msgId\":\"fileAlreadyExists\"");
    assertThat(this.releaseCount(repo)).isEqualTo(1);
    assertThat(storageDirOf(repo).resolve("rules-pkg").resolve(WHEEL)).hasContent("original");
  }

  @Test
  @DisplayName("a file part over the multipart limit is a 413 payloadTooLarge and stores nothing")
  void oversizeUploadIsRefused() throws Exception {
    final var repo = this.publicPypiRepo();

    final var answer = this.upload(repo, this.tokenOf(UserRole.ADMIN), new byte[200 * 1024]);

    assertThat(answer.status()).isEqualTo(413);
    assertThat(answer.body()).contains("\"msgId\":\"payloadTooLarge\"");
    assertThat(this.releaseCount(repo)).isZero();
    assertThat(storageDirOf(repo)).isEmptyDirectory();
  }
}
