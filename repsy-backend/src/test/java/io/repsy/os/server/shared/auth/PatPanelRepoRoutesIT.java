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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1903: the panel routes a command line client needs take a personal access token as the Bearer
 * value: the repo routes ({@code @RepoOperation}, which cover repo get and delete, deploy tokens,
 * packages and scans), the repo list and the repo creation. What it may do is its scopes
 * intersected with its owner's role, and a token that is signed in but not allowed is answered
 * {@code 403 accessDenied} (RPS-1284), where only a missing or invalid token is {@code 401}.
 *
 * <p>Every other panel route stays on the access token of a login: the profile (password and
 * username), the users, usage, the token management routes. A personal access token is refused
 * there with {@code 401}, whatever its scopes and its owner's role.
 */
@DisplayName("Panel routes and personal access tokens")
class PatPanelRepoRoutesIT extends AbstractPatIT {

  private User user;
  private User admin;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.user = this.createUser(uniqueUsername("patuser"), UserRole.USER);
    this.admin = this.createUser(uniqueUsername("patadmin"), UserRole.ADMIN);
    this.repo = this.privateRepo(RepoType.NPM);
  }

  private MockHttpServletResponse panel(
      final AbstractMockHttpServletRequestBuilder<?> request, final Pat pat) throws Exception {
    return this.mockMvc
        .perform(request.with(apiPort()).header(AUTHORIZATION, "Bearer " + pat.secret()))
        .andReturn()
        .getResponse();
  }

  private int panelStatus(final AbstractMockHttpServletRequestBuilder<?> request, final Pat pat)
      throws Exception {
    return this.panel(request, pat).getStatus();
  }

  private String createBody() {
    return "{\"name\":\"%s\",\"type\":\"MAVEN\",\"privateRepo\":true}"
        .formatted(uniqueRepoName("patcreate"));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("repo:read lists and reads repos; a token without it is 403, no token is 401")
  void reading() throws Exception {
    final var reader = this.seedPat(this.user, TokenScope.REPO_READ);
    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);

    assertThat(this.panelStatus(get("/api/repos"), reader)).isEqualTo(200);
    assertThat(this.panelStatus(get("/api/repos/{repo}", this.repo.getName()), reader))
        .isEqualTo(200);
    assertThat(this.panelStatus(get("/api/repos/{repo}/permissions", this.repo.getName()), reader))
        .isEqualTo(200);

    assertThat(this.panelStatus(get("/api/repos"), scanOnly)).isEqualTo(403);
    assertThat(this.panelStatus(get("/api/repos/{repo}", this.repo.getName()), scanOnly))
        .isEqualTo(403);

    assertThat(
            this.mockMvc
                .perform(get("/api/repos/{repo}", this.repo.getName()).with(apiPort()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("the permissions it reports are the effective ones: scopes and owner's role")
  void theEffectivePermissions() throws Exception {
    final var path = "/api/repos/{repo}/permissions";

    final var read =
        this.panel(get(path, this.repo.getName()), this.seedPat(this.admin, TokenScope.REPO_READ));
    final var write =
        this.panel(get(path, this.repo.getName()), this.seedPat(this.admin, TokenScope.REPO_WRITE));
    final var manageUser =
        this.panel(get(path, this.repo.getName()), this.seedPat(this.user, TokenScope.REPO_MANAGE));
    final var manageAdmin =
        this.panel(
            get(path, this.repo.getName()), this.seedPat(this.admin, TokenScope.REPO_MANAGE));

    assertThat(permissions(read)).containsExactly(true, false, false);
    assertThat(permissions(write)).containsExactly(true, true, false);
    assertThat(permissions(manageUser)).containsExactly(true, true, false);
    assertThat(permissions(manageAdmin)).containsExactly(true, true, true);
  }

  private static Boolean[] permissions(final MockHttpServletResponse response) throws Exception {
    final var body = response.getContentAsString();

    return new Boolean[] {
      JsonPath.read(body, "$.canRead"),
      JsonPath.read(body, "$.canWrite"),
      JsonPath.read(body, "$.canManage")
    };
  }

  @Test
  @DisplayName("a repo that does not exist is answered like one the caller may not see")
  void anUnknownRepo() throws Exception {
    final var reader = this.seedPat(this.user, TokenScope.REPO_READ);
    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);

    assertThat(this.panelStatus(get("/api/repos/{repo}", "no-such-repo"), reader)).isEqualTo(404);
    assertThat(this.panelStatus(get("/api/repos/{repo}", "no-such-repo"), scanOnly)).isEqualTo(403);
  }

  @Test
  @DisplayName("a repo route that is read-only for the scopes refuses a write with 403")
  void writingNeedsWrite() throws Exception {
    final var path = "/api/repos/{repo}/artifacts/{name}/versions/{version}/scan";

    assertThat(
            this.panelStatus(
                post(path, this.repo.getName(), "pkg", "1.0.0"),
                this.seedPat(this.user, TokenScope.REPO_READ)))
        .isEqualTo(403);
  }

  // ---------------------------------------------------------------------------------------------
  // Managing
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deleting a repo needs repo:manage and an admin owner")
  void deletingARepo() throws Exception {
    final var path = "/api/repos/{repo}";

    assertThat(
            this.panelStatus(
                delete(path, this.repo.getName()), this.seedPat(this.user, TokenScope.REPO_MANAGE)))
        .isEqualTo(403);
    assertThat(
            this.panelStatus(
                delete(path, this.repo.getName()), this.seedPat(this.admin, TokenScope.REPO_WRITE)))
        .isEqualTo(403);
    assertThat(
            this.panelStatus(
                delete(path, this.repo.getName()),
                this.seedPat(this.admin, TokenScope.REPO_MANAGE)))
        .isEqualTo(204);
  }

  @Test
  @DisplayName("creating a repo needs repo:manage and an admin owner")
  void creatingARepo() throws Exception {
    final var path = "/api/repos";

    assertThat(
            this.panelStatus(
                post(path).contentType(MediaType.APPLICATION_JSON).content(this.createBody()),
                this.seedPat(this.admin, TokenScope.REPO_WRITE)))
        .isEqualTo(403);
    assertThat(
            this.panelStatus(
                post(path).contentType(MediaType.APPLICATION_JSON).content(this.createBody()),
                this.seedPat(this.user, TokenScope.REPO_MANAGE)))
        .isEqualTo(403);
    assertThat(
            this.panelStatus(
                post(path).contentType(MediaType.APPLICATION_JSON).content(this.createBody()),
                this.seedPat(this.admin, TokenScope.REPO_MANAGE)))
        .isEqualTo(201);
  }

  @Test
  @DisplayName("the deploy tokens of a repo and its packages need repo:manage and an admin owner")
  void deployTokensAndPackages() throws Exception {
    final var tokens = "/api/repos/{repo}/deploy-tokens";
    final var delete = "/api/npm/packages/{repo}/{name}";
    final var manage = this.seedPat(this.admin, TokenScope.REPO_MANAGE);
    final var write = this.seedPat(this.admin, TokenScope.REPO_WRITE);

    assertThat(this.panelStatus(get(tokens, this.repo.getName()), manage)).isEqualTo(200);
    assertThat(this.panelStatus(get(tokens, this.repo.getName()), write)).isEqualTo(403);
    assertThat(this.panelStatus(delete(delete, this.repo.getName(), "no-pkg"), write))
        .isEqualTo(403);
    assertThat(this.panelStatus(delete(delete, this.repo.getName(), "no-pkg"), manage))
        .isEqualTo(404);
  }

  // ---------------------------------------------------------------------------------------------
  // What stays on the login token
  // ---------------------------------------------------------------------------------------------

  private record Route(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request) {
    @Override
    public String toString() {
      return this.name;
    }
  }

  static Stream<Arguments> loginOnlyRoutes() {
    return Stream.of(
            new Route("GET /api/profile", () -> get("/api/profile")),
            new Route(
                "PATCH /api/profile/password",
                () ->
                    patch("/api/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"NewPassword2@\"}")),
            new Route(
                "PATCH /api/profile/username",
                () ->
                    patch("/api/profile/username")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone-else\"}")),
            new Route("GET /api/repos/counts", () -> get("/api/repos/counts")),
            new Route("GET /api/usage", () -> get("/api/usage")),
            new Route("GET /api/users", () -> get("/api/users")),
            new Route("GET /api/security/scans", () -> get("/api/security/scans")),
            new Route("GET /api/repos/security-summary", () -> get("/api/repos/security-summary")),
            new Route("GET /api/profile/access-tokens", () -> get("/api/profile/access-tokens")),
            new Route(
                "POST /api/profile/access-tokens",
                () ->
                    post("/api/profile/access-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scopes\":[\"repo:read\"]}")),
            new Route(
                "DELETE /api/profile/access-tokens/{id}",
                () -> delete("/api/profile/access-tokens/" + java.util.UUID.randomUUID())))
        .map(Arguments::of);
  }

  @ParameterizedTest(name = "{0} refuses a personal access token with every scope")
  @MethodSource("loginOnlyRoutes")
  void loginOnly(final Route route) throws Exception {
    final var pat =
        this.seedPat(
            this.admin,
            TokenScope.REPO_MANAGE,
            TokenScope.REPO_WRITE,
            TokenScope.REPO_READ,
            TokenScope.SCAN_READ);

    assertThat(this.panelStatus(route.request().get(), pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("a personal access token is not a Basic password on the panel routes either")
  void notAPanelBasicPassword() throws Exception {
    final var pat = this.seedPat(this.admin, TokenScope.REPO_MANAGE);

    assertThat(
            this.mockMvc
                .perform(
                    get("/api/repos/{repo}", this.repo.getName())
                        .with(apiPort())
                        .header(AUTHORIZATION, basicAuth(this.admin.getUsername(), pat.secret())))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("every use is recorded as the last use of the token")
  void recordsTheLastUse() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_READ);

    assertThat(this.patRepository.findById(pat.id()).orElseThrow().getLastUsedAt()).isNull();

    this.panelStatus(get("/api/repos"), pat);
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.patRepository.findById(pat.id()).orElseThrow().getLastUsedAt()).isNotNull();
  }
}
