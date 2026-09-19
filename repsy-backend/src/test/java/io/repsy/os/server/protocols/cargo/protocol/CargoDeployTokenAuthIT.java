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
package io.repsy.os.server.protocols.cargo.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * RPS-979: a Cargo deploy token is a repo-bound credential, whatever username the client sends with
 * it. {@code GET /{repo}/me} turns Basic credentials into a JWT that carries the deploy token id
 * and that username; the JWT must be authorized as the token on every protocol endpoint, never as
 * the user the username happens to name.
 *
 * <p>Requests go through the wire protocol on the main port, like a real {@code cargo} client.
 */
@DisplayName("Cargo deploy tokens on the wire protocol")
class CargoDeployTokenAuthIT extends AbstractIntegrationTest {

  private static final int PROTOCOL_PORT = 9090;
  private static final int UNAUTHORIZED = 401;
  private static final String CRATE_VERSION = "1.0.0";

  // Publishing schedules an @Async usage update for a repo that only exists in the test
  // transaction.
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private User admin;
  private Repo repo;
  private Repo otherRepo;

  /** Serves the request the way the protocol port does: main port, servlet path = request URI. */
  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  /** A deploy token row plus the secret a client would hold; only the hash is stored. */
  private record DeployToken(RepoDeployToken row, String secret) {}

  private DeployToken seedDeployToken(
      final Repo tokenRepo, final boolean readOnly, final Instant expirationDate) {
    final var secret = TokenFactory.deployToken();
    final var row = new RepoDeployToken();
    row.setRepo(this.repoRepository.getReferenceById(tokenRepo.getId()));
    row.setName("token-" + randomTag());
    row.setUsername(TokenUsernameGenerator.deployTokenUsername());
    row.setToken(DeployTokenHash.hash(secret));
    row.setReadOnly(readOnly);
    row.setExpirationDate(expirationDate);
    row.setTokenDurationDay(30);
    this.deployTokenRepository.saveAndFlush(row);

    return new DeployToken(row, secret);
  }

  private DeployToken seedDeployToken(final Repo tokenRepo, final boolean readOnly) {
    return this.seedDeployToken(tokenRepo, readOnly, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  /** {@code cargo login}: exchanges Basic credentials for the JWT the client stores. */
  private MvcResult login(final String repoName, final String username, final String password)
      throws Exception {
    return this.protocol(
        get("/{repo}/me", repoName).header(AUTHORIZATION, basicAuth(username, password)));
  }

  private String loginToken(final String repoName, final String username, final String password)
      throws Exception {
    final var result = this.login(repoName, username, password);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  private int read(final String repoName, final String authorization) throws Exception {
    return this.protocol(
            get("/{repo}/api/v1/crates/serde/1.0.0/download", repoName)
                .header(AUTHORIZATION, authorization))
        .getResponse()
        .getStatus();
  }

  private int publish(final String repoName, final String authorization, final String crateName)
      throws Exception {
    return this.protocol(
            put("/{repo}/api/v1/crates/new", repoName)
                .header(AUTHORIZATION, authorization)
                .content(publishBody(crateName)))
        .getResponse()
        .getStatus();
  }

  private int renew(final String repoName, final String authorization) throws Exception {
    return this.protocol(get("/{repo}/me", repoName).header(AUTHORIZATION, authorization))
        .getResponse()
        .getStatus();
  }

  /**
   * The body {@code cargo publish} sends: length-prefixed metadata, then the length-prefixed crate.
   */
  private static byte[] publishBody(final String crateName) throws Exception {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[]}")
            .formatted(crateName, CRATE_VERSION)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive(crateName);
    final var body = new ByteArrayOutputStream();

    body.write(littleEndian(metadata.length));
    body.write(metadata);
    body.write(littleEndian(crate.length));
    body.write(crate);

    return body.toByteArray();
  }

  /** A {@code .crate}: a gzipped tarball with one library source file. */
  private static byte[] crateArchive(final String crateName) throws Exception {
    final var source = "pub fn hello() {}\n".getBytes(StandardCharsets.UTF_8);
    final var archive = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(archive))) {
      final var entry = new TarArchiveEntry("%s-%s/src/lib.rs".formatted(crateName, CRATE_VERSION));
      entry.setSize(source.length);
      tar.putArchiveEntry(entry);
      tar.write(source);
      tar.closeArchiveEntry();
    }

    return archive.toByteArray();
  }

  private static byte[] littleEndian(final int value) {
    return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private static String crateName() {
    return "crate-" + randomTag();
  }

  private String bearer(final String jwt) {
    return AuthUtils.AUTH_BEARER + jwt;
  }

  @BeforeEach
  void seedRepos() {
    this.admin = createUser(uniqueUsername("admin"), UserRole.ADMIN);
    this.repo = seedRepo(RepoType.CARGO, uniqueRepoName("cargo-a"), true, null);
    this.otherRepo = seedRepo(RepoType.CARGO, uniqueRepoName("cargo-b"), true, null);
  }

  @Nested
  @DisplayName("with a read-write deploy token")
  class ReadWriteToken {

    @Test
    @DisplayName("cargo login, read and publish keep working in the token's repo")
    void worksInItsRepo() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), token.row().getUsername(), token.secret());

      // The cargo client sends the stored token without a scheme.
      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);
      assertThat(publish(repo.getName(), jwt, crateName())).isEqualTo(200);
    }

    @Test
    @DisplayName("the secret itself keeps working as Basic credentials and as a bearer token")
    void secretWorksDirectly() throws Exception {
      final var token = seedDeployToken(repo, false);

      assertThat(publish(repo.getName(), basicAuth("anyone", token.secret()), crateName()))
          .isEqualTo(200);
      assertThat(publish(repo.getName(), bearer(token.secret()), crateName())).isEqualTo(200);
    }

    @Test
    @DisplayName("the username sent with the secret does not have to be the token's own")
    void usernameIsNotChecked() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), "whatever-the-client-sends", token.secret());

      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token under an admin's username can neither read nor publish in another repo")
    void adminUsernameDoesNotReachOtherRepos() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());

      assertThat(read(otherRepo.getName(), jwt)).isEqualTo(UNAUTHORIZED);
      assertThat(publish(otherRepo.getName(), jwt, crateName())).isEqualTo(UNAUTHORIZED);
      assertThat(read(otherRepo.getName(), bearer(jwt))).isEqualTo(UNAUTHORIZED);
      assertThat(publish(otherRepo.getName(), bearer(jwt), crateName())).isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token under an admin's username cannot be exchanged for a user token")
    void adminUsernameDoesNotBecomeUserToken() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());

      assertThat(renew(repo.getName(), bearer(jwt))).isEqualTo(UNAUTHORIZED);
      assertThat(renew(otherRepo.getName(), bearer(jwt))).isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token that was revoked after login stops working")
    void revokedToken() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());
      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);

      deployTokenRepository.deleteById(token.row().getId());
      deployTokenRepository.flush();

      assertThat(read(repo.getName(), jwt)).isEqualTo(UNAUTHORIZED);
      assertThat(publish(repo.getName(), jwt, crateName())).isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token that expired after login stops working")
    void expiredToken() throws Exception {
      final var token = seedDeployToken(repo, false);
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());
      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);

      token.row().setExpirationDate(Instant.now().minusSeconds(60));
      deployTokenRepository.saveAndFlush(token.row());

      assertThat(read(repo.getName(), jwt)).isEqualTo(UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("with a read-only deploy token")
  class ReadOnlyToken {

    @Test
    @DisplayName("reads, but cannot publish, under an admin's username")
    void cannotPublish() throws Exception {
      final var token = seedDeployToken(repo, true);
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());

      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);
      assertThat(publish(repo.getName(), jwt, crateName())).isEqualTo(UNAUTHORIZED);
      assertThat(publish(repo.getName(), bearer(jwt), crateName())).isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("cannot publish with the secret as Basic credentials either")
    void cannotPublishWithSecret() throws Exception {
      final var token = seedDeployToken(repo, true);

      assertThat(
              publish(repo.getName(), basicAuth(admin.getUsername(), token.secret()), crateName()))
          .isEqualTo(UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("a deploy-token JWT under an admin's username on the other protocols")
  class OtherProtocols {

    /** What Cargo and Docker issue for a deploy token: its id as subject, the client's username. */
    private String deployTokenJwt(final DeployToken token) {
      return bearer(
          jwtUtils.createProtocolToken(
              token.row().getId(),
              admin.getUsername(),
              Duration.ofMinutes(30),
              AuthenticationType.DEPLOY_TOKEN));
    }

    @Test
    @DisplayName("is not the admin on a private Maven repo")
    void maven() throws Exception {
      final var mavenRepo = seedRepo(RepoType.MAVEN, uniqueRepoName("mvn"), true, null);
      final var authorization = deployTokenJwt(seedDeployToken(repo, false));

      final var status =
          protocol(
                  get("/{repo}/com/example/lib/1.0/lib-1.0.pom", mavenRepo.getName())
                      .header(AUTHORIZATION, authorization))
              .getResponse()
              .getStatus();

      assertThat(status).isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("is not the admin on a private npm repo")
    void npm() throws Exception {
      final var npmRepo = seedRepo(RepoType.NPM, uniqueRepoName("npm"), true, null);
      final var authorization = deployTokenJwt(seedDeployToken(repo, false));

      final var status =
          protocol(
                  get("/{repo}/some-package", npmRepo.getName())
                      .header(AUTHORIZATION, authorization))
              .getResponse()
              .getStatus();

      assertThat(status).isEqualTo(UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("with user credentials")
  class UserCredentials {

    @Test
    @DisplayName("cargo login, read, publish and renewing the token keep working")
    void worksForAUser() throws Exception {
      final var user = createUser(uniqueUsername("cargo"), UserRole.USER);
      final var jwt = loginToken(repo.getName(), user.getUsername(), VALID_PASSWORD);

      assertThat(read(repo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);
      assertThat(publish(repo.getName(), jwt, crateName())).isEqualTo(200);
      assertThat(read(otherRepo.getName(), jwt)).isNotEqualTo(UNAUTHORIZED);
      assertThat(renew(repo.getName(), bearer(jwt))).isEqualTo(200);
    }

    @Test
    @DisplayName("an admin's username with a deploy token's secret is not a login")
    void deployTokenSecretIsNotAPassword() throws Exception {
      final var token = seedDeployToken(otherRepo, false);
      final var mavenRepo = seedRepo(RepoType.MAVEN, uniqueRepoName("mvn"), true, null);
      final var mavenToken = seedDeployToken(mavenRepo, false);

      assertThat(login(repo.getName(), admin.getUsername(), "wrong").getResponse().getStatus())
          .isEqualTo(UNAUTHORIZED);
      // A token of another protocol's repo is not a Cargo credential.
      assertThat(
              login(repo.getName(), admin.getUsername(), mavenToken.secret())
                  .getResponse()
                  .getStatus())
          .isEqualTo(UNAUTHORIZED);
      // A Cargo token of another repo logs in, but is bound to that repo when it is used.
      final var jwt = loginToken(repo.getName(), admin.getUsername(), token.secret());
      assertThat(read(repo.getName(), jwt)).isEqualTo(UNAUTHORIZED);
    }
  }
}
