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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1424: {@code npm unpublish} removes stored files, so it needs MANAGE (the ADMIN role) like
 * the panel's delete, and a deploy token never has MANAGE. {@code npm deprecate} and {@code npm
 * dist-tag} only change what the registry advertises and stay WRITE: a USER-role account and a
 * read-write deploy token may still run them.
 *
 * <p>Every credential an npm client can hold is tried: Basic with the account password, Basic with
 * a deploy token as the password, the raw deploy token as a Bearer value, and the JWT {@code npm
 * login} exchanges a deploy token for. A refused unpublish answers 401 with a challenge and leaves
 * the package as it was. {@link UsageUpdateService} is mocked and every test runs in the default
 * rolled-back transaction, like {@link NpmUnpublishProtocolIT}.
 */
@DisplayName("npm unpublish needs MANAGE, deprecate and dist-tag stay WRITE (RPS-1424)")
class NpmUnpublishPermissionIT extends AbstractIntegrationTest {

  private static final String PACKAGE = "perm-package";
  private static final String PACKUMENT = "/{repo}/" + PACKAGE;
  private static final String REV = "undefined";
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String DIST_TAG = "/{repo}/-/package/" + PACKAGE + "/dist-tags/{tag}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo npmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("rps1424"), true, null);
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

  /** The JWT an npm client stores after {@code npm login} with a deploy-token secret. */
  private String loginJwt(final Repo repo, final String secret) throws Exception {
    final var response =
        this.protocol(
            put(LOGIN, repo.getName(), "whoever")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", "whoever", "password", secret))));

    assertThat(response.getStatus()).isEqualTo(201);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  /** Every way a client that is not an admin can present itself, by a readable name. */
  private Map<String, String> nonAdminCredentials(final Repo repo, final boolean readOnly)
      throws Exception {

    final var credentials = new LinkedHashMap<String, String>();
    final var secret = this.seedToken(repo, readOnly);
    final var kind = readOnly ? "read-only" : "read-write";

    credentials.put(
        kind + " deploy token as the Basic password", basicAuth("whatever-typed", secret));
    credentials.put(kind + " deploy token as a raw Bearer", AuthUtils.AUTH_BEARER + secret);
    credentials.put(kind + " deploy token exchanged by npm login", this.loginJwt(repo, secret));

    return credentials;
  }

  private String userCredentials() {
    final var user = this.createUser(uniqueUsername("rps1424"), UserRole.USER);

    return basicAuth(user.getUsername(), VALID_PASSWORD);
  }

  private byte[] publishBody(final Repo repo, final String version) {
    return NpmPublishBodies.body(this.objectMapper, repo.getName(), PACKAGE, version, Map.of());
  }

  private void publish(final Repo repo, final String version) throws Exception {
    final var response =
        this.protocol(
            put(PACKUMENT, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.publishBody(repo, version)));

    assertThat(response.getStatus()).as("publish %s", version).isEqualTo(200);
  }

  private Map<String, Object> packument(final Repo repo) throws Exception {
    final var response =
        this.protocol(
            get(PACKUMENT, repo.getName())
                .queryParam("write", "true")
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    assertThat(response.getStatus()).isEqualTo(200);

    return this.objectMapper.readValue(
        response.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  private MockHttpServletResponse putRevPackument(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        put(PACKUMENT + "/-rev/{rev}", repo.getName(), REV)
            .header(AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"versions\":{}}"));
  }

  private MockHttpServletResponse deleteTarball(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        delete(
                "/{repo}/" + PACKAGE + "/-/" + PACKAGE + "-1.0.0.tgz/-rev/{rev}",
                repo.getName(),
                REV)
            .header(AUTHORIZATION, authorization));
  }

  private MockHttpServletResponse deleteWholePackage(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        delete(PACKUMENT + "/-rev/{rev}", repo.getName(), REV)
            .header(AUTHORIZATION, authorization));
  }

  private void assertRefusedWithChallenge(
      final MockHttpServletResponse response, final String credential, final String step) {

    assertThat(response.getStatus()).as("%s with %s", step, credential).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .as("%s with %s answers a challenge", step, credential)
        .isNotBlank();
  }

  private void assertUntouched(final Repo repo) throws Exception {
    this.entityManager.flush();
    this.entityManager.clear();

    final var stored =
        this.npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), null, PACKAGE);

    assertThat(stored).as("the package is still stored").isPresent();
    assertThat(this.packageVersionRepository.findByNpmPackageId(stored.get().getId()))
        .as("its version is still stored")
        .hasSize(1);
    assertThat(
            this.protocol(
                    get("/{repo}/" + PACKAGE + "/-/" + PACKAGE + "-1.0.0.tgz", repo.getName())
                        .header(AUTHORIZATION, this.adminProtocolBearerToken()))
                .getStatus())
        .as("its tarball is still served")
        .isEqualTo(200);
  }

  @Test
  @DisplayName("a USER-role account cannot unpublish: all three requests answer 401")
  void userCannotUnpublish() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");
    final var user = this.userCredentials();

    this.assertRefusedWithChallenge(this.putRevPackument(repo, user), "a USER", "PUT packument");
    this.assertRefusedWithChallenge(this.deleteTarball(repo, user), "a USER", "DELETE tarball");
    this.assertRefusedWithChallenge(
        this.deleteWholePackage(repo, user), "a USER", "DELETE package");
    this.assertUntouched(repo);
  }

  @Test
  @DisplayName("a read-write deploy token cannot unpublish, however it is presented")
  void readWriteDeployTokenCannotUnpublish() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    for (final var credential : this.nonAdminCredentials(repo, false).entrySet()) {
      final var authorization = credential.getValue();

      this.assertRefusedWithChallenge(
          this.putRevPackument(repo, authorization), credential.getKey(), "PUT packument");
      this.assertRefusedWithChallenge(
          this.deleteTarball(repo, authorization), credential.getKey(), "DELETE tarball");
      this.assertRefusedWithChallenge(
          this.deleteWholePackage(repo, authorization), credential.getKey(), "DELETE package");
    }
    this.assertUntouched(repo);
  }

  @Test
  @DisplayName("a read-only deploy token cannot unpublish either")
  void readOnlyDeployTokenCannotUnpublish() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    for (final var credential : this.nonAdminCredentials(repo, true).entrySet()) {
      this.assertRefusedWithChallenge(
          this.putRevPackument(repo, credential.getValue()), credential.getKey(), "PUT packument");
      this.assertRefusedWithChallenge(
          this.deleteWholePackage(repo, credential.getValue()),
          credential.getKey(),
          "DELETE package");
    }
    this.assertUntouched(repo);
  }

  @Test
  @DisplayName("an admin unpublishes: the packument PUT and the tarball DELETE answer 200")
  void adminCanUnpublish() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");
    this.publish(repo, "1.1.0");
    final var admin = this.adminProtocolBearerToken();

    final var packument = this.packument(repo);
    @SuppressWarnings("unchecked")
    final var versions = (Map<String, Object>) packument.get("versions");
    versions.remove("1.1.0");
    @SuppressWarnings("unchecked")
    final var tags = (Map<String, String>) packument.get("dist-tags");
    tags.put("latest", "1.0.0");

    final var putResponse =
        this.protocol(
            put(PACKUMENT + "/-rev/{rev}", repo.getName(), REV)
                .header(AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsBytes(packument)));
    final var deleteResponse =
        this.protocol(
            delete(
                    "/{repo}/" + PACKAGE + "/-/" + PACKAGE + "-1.1.0.tgz/-rev/{rev}",
                    repo.getName(),
                    REV)
                .header(AUTHORIZATION, admin));

    assertThat(putResponse.getStatus()).isEqualTo(200);
    assertThat(deleteResponse.getStatus()).isEqualTo(200);
    assertThat(
            this.protocol(
                    get("/{repo}/" + PACKAGE + "/-/" + PACKAGE + "-1.1.0.tgz", repo.getName())
                        .header(AUTHORIZATION, admin))
                .getStatus())
        .as("the unpublished tarball is gone")
        .isEqualTo(404);
  }

  @Test
  @DisplayName("an admin deletes the whole package with DELETE /-rev/")
  void adminCanDeleteThePackage() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    final var response = this.deleteWholePackage(repo, this.adminProtocolBearerToken());

    assertThat(response.getStatus()).isEqualTo(200);
    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(this.npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), null, PACKAGE))
        .isEmpty();
  }

  private MockHttpServletResponse deprecate(final Repo repo, final String authorization)
      throws Exception {

    final var packument = this.packument(repo);
    @SuppressWarnings("unchecked")
    final var versions = (Map<String, Map<String, Object>>) packument.get("versions");
    versions.get("1.0.0").put("deprecated", "use something else");

    return this.protocol(
        put(PACKUMENT, repo.getName())
            .header(AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.objectMapper.writeValueAsBytes(packument)));
  }

  private MockHttpServletResponse addTag(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        put(DIST_TAG, repo.getName(), "next")
            .header(AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content("\"1.0.0\""));
  }

  private MockHttpServletResponse removeTag(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        delete(DIST_TAG, repo.getName(), "next").header(AUTHORIZATION, authorization));
  }

  private void assertSoftOperationsSucceed(
      final Repo repo, final String credential, final String authorization) throws Exception {

    assertThat(this.deprecate(repo, authorization).getStatus())
        .as("deprecate with %s", credential)
        .isEqualTo(200);
    assertThat(this.addTag(repo, authorization).getStatus())
        .as("dist-tag add with %s", credential)
        .isEqualTo(200);
    assertThat(this.removeTag(repo, authorization).getStatus())
        .as("dist-tag rm with %s", credential)
        .isEqualTo(200);
  }

  @Test
  @DisplayName("a USER-role account still deprecates and adds and removes dist-tags")
  void userKeepsTheSoftOperations() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    this.assertSoftOperationsSucceed(repo, "a USER", this.userCredentials());
  }

  @Test
  @DisplayName("a read-write deploy token still deprecates and adds and removes dist-tags")
  void readWriteDeployTokenKeepsTheSoftOperations() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    for (final var credential : this.nonAdminCredentials(repo, false).entrySet()) {
      this.assertSoftOperationsSucceed(repo, credential.getKey(), credential.getValue());
    }
  }

  @Test
  @DisplayName("a read-only deploy token still cannot deprecate or retag")
  void readOnlyDeployTokenStillCannotDeprecateOrRetag() throws Exception {
    final var repo = this.npmRepo();
    this.publish(repo, "1.0.0");

    for (final var credential : this.nonAdminCredentials(repo, true).entrySet()) {
      assertThat(this.deprecate(repo, credential.getValue()).getStatus())
          .as("deprecate with %s", credential.getKey())
          .isEqualTo(401);
      assertThat(this.addTag(repo, credential.getValue()).getStatus())
          .as("dist-tag add with %s", credential.getKey())
          .isEqualTo(401);
    }
  }
}
