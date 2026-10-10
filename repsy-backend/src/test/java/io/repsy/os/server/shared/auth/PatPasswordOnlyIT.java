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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * RPS-1903: a personal access token authenticates by its secret alone, like a deploy token
 * (RPS-1312). Every protocol that takes Basic credentials hands the password to the token lookup
 * before it looks at the username, so the username is a label: a wrong, an empty or another user's
 * name with a valid secret is accepted, and a wrong secret with the owner's own username is
 * refused.
 *
 * <p>Each acceptance is paired with the same request without the token, which is refused, so a
 * route that stopped authenticating does not pass for "username ignored". A protocol read is
 * answered {@code 401} when authentication fails and by the route's own status when it succeeds
 * (the resources do not exist, so most answer {@code 404}). Docker takes no Basic credentials on
 * {@code /v2}, so it is covered where its token exchange is ({@code DockerPatTokenExchangeIT}).
 */
@DisplayName("A personal access token authenticates by its secret, whatever username is sent")
class PatPasswordOnlyIT extends AbstractPatIT {

  private static final String MODULE = "example.com/mod";
  private static final String WRONG_USERNAME = "not-the-owner";
  private static final String CHECKSUM = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

  /** What the client sends in the username field of the Basic credentials. */
  enum Username {
    /** The owner's own username. */
    OWNER,
    /** A name that belongs to nobody. */
    WRONG,
    /** Nothing at all, as {@code :secret}. */
    EMPTY,
    /** An existing user who is not the owner. */
    ANOTHER_USER
  }

  /** A read that needs authentication on a private repo, and its status once authenticated. */
  private record Read(RepoType type, String path, int authenticatedStatus) {

    @Override
    public String toString() {
      return this.type + " " + this.path;
    }
  }

  /** One read per protocol that takes Basic credentials. */
  static Stream<Read> reads() {
    return Stream.of(
        new Read(RepoType.MAVEN, "/{repo}/com/example/lib/1.0/lib-1.0.pom", 404),
        new Read(RepoType.NPM, "/{repo}/some-package", 404),
        new Read(RepoType.PYPI, "/{repo}/simple/some-package/", 404),
        new Read(RepoType.HELM, "/{repo}/index.yaml", 200),
        new Read(RepoType.CARGO, "/{repo}/so/me/some-crate", 404),
        new Read(RepoType.GOLANG, "/{repo}/" + MODULE + "/@v/list", 404),
        new Read(RepoType.RUBY, "/{repo}/names", 200),
        // The NuGet service index is public even on a private repo, so read a package instead.
        new Read(RepoType.NUGET, "/{repo}/v3/package/some.pkg/index.json", 404));
  }

  static Stream<Arguments> readsByUsername() {
    return reads()
        .flatMap(
            read -> Stream.of(Username.values()).map(username -> Arguments.of(read, username)));
  }

  static Stream<Arguments> readsByFoulUsername() {
    return reads()
        .flatMap(
            read ->
                Stream.of(Username.OWNER, Username.WRONG, Username.EMPTY)
                    .map(username -> Arguments.of(read, username)));
  }

  private User owner;

  @BeforeEach
  void createOwner() {
    this.owner = this.createUser(uniqueUsername("patowner"), UserRole.USER);
  }

  private String usernameFor(final Username kind) {
    return switch (kind) {
      case OWNER -> this.owner.getUsername();
      case WRONG -> WRONG_USERNAME;
      case EMPTY -> "";
      case ANOTHER_USER -> this.createUser(uniqueUsername("other"), UserRole.USER).getUsername();
      case null -> throw new IllegalArgumentException("no username kind");
    };
  }

  @Nested
  @DisplayName("is accepted with any username")
  class Accepted {

    @ParameterizedTest(name = "{0} with username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.PatPasswordOnlyIT#readsByUsername")
    void basicRead(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var pat = seedPat(owner, TokenScope.REPO_READ);
      final var header = basicAuth(usernameFor(username), pat.secret());

      assertThat(status(get(read.path(), repo.getName()))).isEqualTo(401);
      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(read.authenticatedStatus());
    }

    @ParameterizedTest(name = "Maven write with username {0}")
    @EnumSource(Username.class)
    void mavenWrite(final Username username) throws Exception {
      // A checksum file is stored without registering an artifact, which the rolled-back test
      // transaction could not hold: it needs WRITE all the same.
      final var repo = privateRepo(RepoType.MAVEN);
      final var pat = seedPat(owner, TokenScope.REPO_WRITE);
      final var path = "/{repo}/com/example/lib/1.0/lib-1.0.pom.sha1";
      final var header = basicAuth(usernameFor(username), pat.secret());

      assertThat(status(put(path, repo.getName()).content(CHECKSUM))).isEqualTo(401);
      assertThat(status(put(path, repo.getName()).header(AUTHORIZATION, header).content(CHECKSUM)))
          .isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("is refused whatever the username is")
  class Refused {

    @ParameterizedTest(name = "{0} with the owner's username and a wrong secret; username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.PatPasswordOnlyIT#readsByFoulUsername")
    void wrongSecret(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var pat = seedPat(owner, TokenScope.REPO_READ);
      final var unknown = basicAuth(usernameFor(username), TokenFactory.personalAccessToken());

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, unknown)))
          .isEqualTo(401);
      // The username alone never carries a request: the valid secret is what does.
      assertThat(
              status(
                  get(read.path(), repo.getName())
                      .header(AUTHORIZATION, basicAuth(owner.getUsername(), pat.secret()))))
          .isEqualTo(read.authenticatedStatus());
    }

    @ParameterizedTest(name = "{0} with an expired token; username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.PatPasswordOnlyIT#readsByFoulUsername")
    void expiredToken(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var pat = seedPat(owner, TokenScope.REPO_READ);
      final var header = basicAuth(usernameFor(username), pat.secret());

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(read.authenticatedStatus());

      expire(pat);

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(401);
    }

    @ParameterizedTest(name = "{0} with the secret of a token as the Bearer value")
    @MethodSource("io.repsy.os.server.shared.auth.PatPasswordOnlyIT#reads")
    void rawBearerIsAccepted(final Read read) throws Exception {
      final var repo = privateRepo(read.type());
      final var pat = seedPat(owner, TokenScope.REPO_READ);

      assertThat(
              status(
                  get(read.path(), repo.getName()).header(AUTHORIZATION, "Bearer " + pat.secret())))
          .isEqualTo(read.authenticatedStatus());
      assertThat(
              status(
                  get(read.path(), repo.getName())
                      .header(AUTHORIZATION, "Bearer " + TokenFactory.personalAccessToken())))
          .isEqualTo(401);
    }
  }
}
