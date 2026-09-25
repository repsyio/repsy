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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.bytes;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1434: {@code /v2/token} records the scopes a client asked for, and a manifest DELETE checks
 * that the token was issued for {@code delete} on that image. Before, a token asked for {@code
 * repository:r/i:pull} deleted a manifest (202) for an administrator, because the token carried
 * nothing but the user. Issuing the token is still not refused for a scope; each request is.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data.
 */
@DisplayName("Docker token scope on DELETE (RPS-1434)")
class DockerTokenScopeIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String LAYER = "layer-one";

  @Autowired private WebApplicationContext webApplicationContext;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private DockerWire adminWire;
  private String adminUsername;

  @BeforeEach
  void setUp() {
    this.adminUsername = this.createUser(uniqueUsername("admin"), UserRole.ADMIN).getUsername();
    this.adminWire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.adminProtocolBearerToken());
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  /** Pushes an image with the administrator's token and answers its manifest JSON. */
  private String push(final Repo repo) throws Exception {
    this.adminWire.pushBlobsOf(repo, IMAGE, LAYER);
    final var manifest = imageManifest(LAYER);
    final var response = this.adminWire.putImage(repo, IMAGE, "latest", manifest);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return manifest;
  }

  private String scope(final Repo repo, final String actions) {
    return "repository:%s/%s:%s".formatted(repo.getName(), IMAGE, actions);
  }

  /**
   * What every client does: the administrator's Basic credentials for a token, for {@code scopes}.
   */
  private String tokenFor(final String... scopes) throws Exception {
    final var request =
        post("/v2/token")
            .header(AUTHORIZATION, basicAuth(this.adminUsername, VALID_PASSWORD))
            .with(protocolPort());

    for (final var scope : scopes) {
      request.param("scope", scope);
    }

    final var response = this.mockMvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  private MockHttpServletResponse deleteReference(
      final Repo repo, final String reference, final String token) throws Exception {
    return this.mockMvc
        .perform(
            delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void assertRefusedAndUntouched(final Repo repo, final String manifest, final String token)
      throws Exception {

    final var byTag = this.deleteReference(repo, "latest", token);
    final var byDigest = this.deleteReference(repo, sha256(bytes(manifest)), token);

    for (final var response : new MockHttpServletResponse[] {byTag, byDigest}) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
          .isEqualTo("UNAUTHORIZED");
      // The client is told which scope to ask for, so that it can ask again.
      assertThat(response.getHeader(WWW_AUTHENTICATE))
          .startsWith("Bearer realm=")
          .contains("scope=\"repository:%s/%s:delete\"".formatted(repo.getName(), IMAGE))
          .endsWith("error=\"insufficient_scope\"");
    }
    assertThat(this.adminWire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.adminWire.getManifest(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("a token asked for pull only cannot delete, for an administrator either")
  void pullOnlyTokenCannotDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);

    this.assertRefusedAndUntouched(repo, manifest, this.tokenFor(this.scope(repo, "pull")));
  }

  @Test
  @DisplayName("a token asked for push,pull cannot delete")
  void pushPullTokenCannotDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);

    this.assertRefusedAndUntouched(repo, manifest, this.tokenFor(this.scope(repo, "push,pull")));
  }

  @Test
  @DisplayName("a token asked for no scope at all cannot delete")
  void tokenWithoutScopeCannotDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);

    this.assertRefusedAndUntouched(repo, manifest, this.tokenFor());
  }

  @Test
  @DisplayName("a token asked to delete another image or repo cannot delete this one")
  void tokenForAnotherImageCannotDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);

    this.assertRefusedAndUntouched(
        repo,
        manifest,
        this.tokenFor(
            "repository:%s/other:delete".formatted(repo.getName()),
            "repository:someone-else/%s:delete".formatted(IMAGE)));
  }

  @Test
  @DisplayName("a token asked for the delete action deletes by tag and by digest")
  void deleteTokenCanDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);
    final var token = this.tokenFor(this.scope(repo, "delete"));

    final var byTag = this.deleteReference(repo, "latest", token);
    assertThat(byTag.getStatus()).as(byTag.getContentAsString()).isEqualTo(202);

    final var byDigest = this.deleteReference(repo, sha256(bytes(manifest)), token);
    assertThat(byDigest.getStatus()).as(byDigest.getContentAsString()).isEqualTo(202);
    assertThat(this.adminWire.getManifest(repo, IMAGE, "latest").getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("push,pull,delete (what crane asks for) and * both delete")
  void pushPullDeleteAndWildcardCanDelete() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo);

    final var withAllActions =
        this.deleteReference(repo, "latest", this.tokenFor(this.scope(repo, "push,pull,delete")));
    assertThat(withAllActions.getStatus()).isEqualTo(202);

    final var manifest = this.push(repo);
    final var withWildcard =
        this.deleteReference(repo, sha256(bytes(manifest)), this.tokenFor(this.scope(repo, "*")));
    assertThat(withWildcard.getStatus()).isEqualTo(202);
  }

  @Test
  @DisplayName("several scopes, in one parameter or in several, are all recorded")
  void severalScopesAreRecorded() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo);
    final var pull = "repository:%s/other:pull".formatted(repo.getName());

    final var inOneParameter = this.tokenFor(pull + " " + this.scope(repo, "push,pull,delete"));
    assertThat(this.deleteReference(repo, "latest", inOneParameter).getStatus()).isEqualTo(202);

    this.push(repo);
    final var inTwoParameters = this.tokenFor(pull, this.scope(repo, "delete"));
    assertThat(this.deleteReference(repo, "latest", inTwoParameters).getStatus()).isEqualTo(202);
  }

  @Test
  @DisplayName("a token asked for push,pull still pushes and pulls")
  void pushAndPullAreUnchanged() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);
    final var token = this.tokenFor(this.scope(repo, "push,pull"));
    final var wire =
        new DockerWire(this.mockMvc, this.webApplicationContext, protocolPort(), token);

    assertThat(wire.getManifest(repo, IMAGE, "latest").getContentAsString()).isEqualTo(manifest);
    assertThat(wire.putImage(repo, IMAGE, "other", manifest).getStatus()).isEqualTo(201);
  }

  @Test
  @DisplayName("a token asked for pull still pulls")
  void pullIsUnchanged() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo);
    final var wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.tokenFor(this.scope(repo, "pull")));

    assertThat(wire.getManifest(repo, IMAGE, "latest").getContentAsString()).isEqualTo(manifest);
  }
}
