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

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-979: the JWT Cargo hands a deploy token at {@code /me} carries the username the client typed
 * into the Basic credentials. It has to be authorized as the deploy token (bound to its repo,
 * read-only respected), never as the user of that name, and {@code /me} must not swap it for that
 * user's token.
 *
 * <p>Every test mints the JWT through the real {@code /me} flow with an existing admin's username
 * and a deploy-token secret, so a regression shows up whichever side (issuer or verifier) moves.
 */
@DisplayName("Cargo deploy-token JWT is a deploy token, not the user its username names")
class CargoDeployTokenAuthIT extends AbstractIntegrationTest {

  private static final String CRATE = "deploy-crate";
  private static final String INDEX = "/de/pl/" + CRATE;
  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String PACKUMENT = "/{repo}/some-package";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getResponse().getStatus();
  }

  private static String basic(final String username, final String password) {
    final var credentials = username + ":" + password;

    return AuthUtils.AUTH_BASIC
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static String bearer(final String token) {
    return AuthUtils.AUTH_BEARER + token;
  }

  /**
   * A {@code cargo publish} body: length-prefixed JSON metadata, then the length-prefixed crate.
   */
  private static byte[] publishBody() {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"1.0.0\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"deploy token fixture\",\"license\":\"MIT\"}")
            .formatted(CRATE)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive();
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
    out.writeBytes(metadata);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crate.length).array());
    out.writeBytes(crate);

    return out.toByteArray();
  }

  /** A minimal {@code .crate}: a gzipped tarball holding a manifest and a library root. */
  private static byte[] crateArchive() {
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, CRATE + "-1.0.0/Cargo.toml", "[package]\nname = \"" + CRATE + "\"\n");
      addEntry(tar, CRATE + "-1.0.0/src/lib.rs", "");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  private static void addEntry(
      final TarArchiveOutputStream tar, final String name, final String content)
      throws IOException {
    final var data = content.getBytes(StandardCharsets.UTF_8);
    final var entry = new TarArchiveEntry(name);

    entry.setSize(data.length);
    tar.putArchiveEntry(entry);
    tar.write(data);
    tar.closeArchiveEntry();
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedToken(final Repo repo, final boolean readOnly) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  /** The JWT {@code /me} answers a deploy-token secret with, sent as an admin's username. */
  private String deployJwt(final Repo repo, final User asUser, final String secret)
      throws Exception {
    final var result =
        this.protocol(
            get("/{repo}/me", repo.getName())
                .header(AUTHORIZATION, basic(asUser.getUsername(), secret)));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  private RepoDeployToken tokenOf(final Repo repo, final String secret) {
    return this.deployTokenRepository
        .findByRepoIdAndToken(repo.getId(), DeployTokenHash.hash(secret))
        .orElseThrow();
  }

  private Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps979"), true, null);
  }

  /** A read-write deploy token of {@code repoA}, its JWT under an admin's name, and repo B. */
  private final class Scenario {
    final User admin = createUser(uniqueUsername("victim"), UserRole.ADMIN);
    final Repo repoA = privateRepo(RepoType.CARGO);
    final Repo repoB = privateRepo(RepoType.CARGO);
    final String readWriteSecret = seedToken(this.repoA, false);
    final String readOnlySecret = seedToken(this.repoA, true);
  }

  @Nested
  @DisplayName("the JWT /me issues")
  class Issuance {

    @Test
    @DisplayName("is a deploy-token JWT that carries the username the client typed")
    void carriesTheClientChosenUsername() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readOnlySecret);
      final var decoded = JWT.decode(jwt);

      // Pins the premise of the attack: without it the tests below would prove nothing.
      assertThat(decoded.getClaim("username").asString()).isEqualTo(s.admin.getUsername());
      assertThat(decoded.getClaim("authentication_type").asString()).isEqualTo("deploy_token");
      assertThat(decoded.getSubject()).isNotEqualTo(s.admin.getId().toString());
    }
  }

  @Nested
  @DisplayName("GET /{repo}/me")
  class Me {

    @Test
    @DisplayName("does not exchange a deploy-token JWT for the token of the user it names")
    void refusesToMintAUserToken() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readOnlySecret);

      final var result =
          protocol(get("/{repo}/me", s.repoA.getName()).header(AUTHORIZATION, bearer(jwt)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).doesNotContain("\"token\"");
    }

    @Test
    @DisplayName("still exchanges a user's token for a fresh one")
    void refreshesAUserToken() throws Exception {
      final var user = createUser(uniqueUsername("cargo"), UserRole.USER);
      final var repo = privateRepo(RepoType.CARGO);
      final var first =
          protocol(
              get("/{repo}/me", repo.getName())
                  .header(AUTHORIZATION, basic(user.getUsername(), VALID_PASSWORD)));
      final var token = JsonPath.<String>read(first.getResponse().getContentAsString(), "$.token");

      final var second =
          protocol(get("/{repo}/me", repo.getName()).header(AUTHORIZATION, bearer(token)));

      assertThat(second.getResponse().getStatus()).isEqualTo(200);
      assertThat(JsonPath.<String>read(second.getResponse().getContentAsString(), "$.token"))
          .isNotBlank();
    }
  }

  @Nested
  @DisplayName("Cargo endpoints with the deploy-token JWT")
  class CargoEndpoints {

    @Test
    @DisplayName("cannot read another repo, however the username claim reads")
    void cannotReadAnotherRepo() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readOnlySecret);

      assertThat(
              status(get("/{repo}" + INDEX, s.repoB.getName()).header(AUTHORIZATION, bearer(jwt))))
          .isEqualTo(401);
    }

    @Test
    @DisplayName("cannot write another repo, even with a read-write token")
    void cannotWriteAnotherRepo() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readWriteSecret);

      assertThat(
              status(
                  put(PUBLISH, s.repoB.getName())
                      .header(AUTHORIZATION, bearer(jwt))
                      .content(publishBody())))
          .isEqualTo(401);
    }

    @Test
    @DisplayName("of a read-only token cannot publish to its own repo, and can still read it")
    void readOnlyTokenCannotPublish() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readOnlySecret);

      assertThat(
              status(
                  put(PUBLISH, s.repoA.getName())
                      .header(AUTHORIZATION, bearer(jwt))
                      .content(publishBody())))
          .isEqualTo(401);
      assertThat(
              status(get("/{repo}" + INDEX, s.repoA.getName()).header(AUTHORIZATION, bearer(jwt))))
          .isNotEqualTo(401);
    }

    @Test
    @DisplayName("of a read-write token publishes to its own repo, and a read-only one reads it")
    void readWriteTokenPublishes() throws Exception {
      final var s = new Scenario();
      final var writeJwt = deployJwt(s.repoA, s.admin, s.readWriteSecret);
      final var readJwt = deployJwt(s.repoA, s.admin, s.readOnlySecret);

      assertThat(
              status(
                  put(PUBLISH, s.repoA.getName())
                      .header(AUTHORIZATION, bearer(writeJwt))
                      .content(publishBody())))
          .isEqualTo(200);
      assertThat(
              status(
                  get("/{repo}" + INDEX, s.repoA.getName()).header(AUTHORIZATION, bearer(readJwt))))
          .isEqualTo(200);
    }

    @Test
    @DisplayName("stops working once its deploy token is revoked")
    void revokedTokenIsRefused() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readWriteSecret);

      deployTokenRepository.delete(tokenOf(s.repoA, s.readWriteSecret));
      entityManager.flush();

      assertThat(
              status(get("/{repo}" + INDEX, s.repoA.getName()).header(AUTHORIZATION, bearer(jwt))))
          .isEqualTo(401);
    }

    @Test
    @DisplayName("stops working once its deploy token has expired")
    void expiredTokenIsRefused() throws Exception {
      final var s = new Scenario();
      final var jwt = deployJwt(s.repoA, s.admin, s.readWriteSecret);
      final var token = tokenOf(s.repoA, s.readWriteSecret);

      token.setExpirationDate(Instant.now().minusSeconds(60));
      deployTokenRepository.saveAndFlush(token);

      final var result =
          protocol(get("/{repo}" + INDEX, s.repoA.getName()).header(AUTHORIZATION, bearer(jwt)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).contains("deployTokenExpired");
    }

    @Test
    @DisplayName("of a deploy token sent as Basic credentials still works, read-only respected")
    void basicDeployTokenStillWorks() throws Exception {
      final var s = new Scenario();
      final var writer = basic(s.admin.getUsername(), s.readWriteSecret);
      final var reader = basic(s.admin.getUsername(), s.readOnlySecret);

      assertThat(
              status(
                  put(PUBLISH, s.repoA.getName())
                      .header(AUTHORIZATION, writer)
                      .content(publishBody())))
          .isEqualTo(200);
      assertThat(
              status(
                  put(PUBLISH, s.repoA.getName())
                      .header(AUTHORIZATION, reader)
                      .content(publishBody())))
          .isEqualTo(401);
      assertThat(status(get("/{repo}" + INDEX, s.repoA.getName()).header(AUTHORIZATION, reader)))
          .isEqualTo(200);
    }

    @Test
    @DisplayName("of a user token still reads and publishes")
    void userTokenStillWorks() throws Exception {
      final var user = createUser(uniqueUsername("cargo"), UserRole.USER);
      final var repo = privateRepo(RepoType.CARGO);
      final var login =
          protocol(
              get("/{repo}/me", repo.getName())
                  .header(AUTHORIZATION, basic(user.getUsername(), VALID_PASSWORD)));
      final var token =
          bearer(JsonPath.<String>read(login.getResponse().getContentAsString(), "$.token"));

      assertThat(
              status(
                  put(PUBLISH, repo.getName()).header(AUTHORIZATION, token).content(publishBody())))
          .isEqualTo(200);
      assertThat(status(get("/{repo}" + INDEX, repo.getName()).header(AUTHORIZATION, token)))
          .isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("other protocols with the Cargo deploy-token JWT")
  class OtherProtocols {

    @Test
    @DisplayName("npm does not resolve the username claim to a user")
    void npmRefusesTheJwt() throws Exception {
      final var s = new Scenario();
      final var npmRepo = privateRepo(RepoType.NPM);
      final var jwt = deployJwt(s.repoA, s.admin, s.readWriteSecret);

      assertThat(status(get(PACKUMENT, npmRepo.getName()).header(AUTHORIZATION, bearer(jwt))))
          .isEqualTo(401);
    }
  }
}
