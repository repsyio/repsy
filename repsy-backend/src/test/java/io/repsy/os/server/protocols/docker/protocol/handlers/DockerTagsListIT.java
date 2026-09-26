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
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.index;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1489: {@code GET /v2/<repo>/<image>/tags/list} over the real wire protocol. Until then the
 * route did not exist and answered {@code 404 unknownPath} to everyone, before authentication, so
 * {@code crane ls}, {@code skopeo list-tags} and {@code regctl tag ls} could not list a Repsy repo.
 * The tests pin the listing (lexical order, repo-qualified name), the {@code n} / {@code last}
 * pagination with its {@code Link} header, the permission check of a read, and that an image or
 * repo that does not exist answers like every other Docker route.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data.
 */
@DisplayName("Docker tags/list (RPS-1489)")
class DockerTagsListIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final Pattern NEXT_LINK = Pattern.compile("^<(?<url>[^>]+)>; rel=\"next\"$");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private DockerWire wire;
  private String adminToken;

  @BeforeEach
  void setUpWire() {
    this.adminToken = this.adminProtocolBearerToken();
    this.wire =
        new DockerWire(this.mockMvc, this.webApplicationContext, protocolPort(), this.adminToken);
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private Repo privateDockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), true, null);
  }

  /** Pushes an image manifest built from {@code layer} under each of the references. */
  private String push(final Repo repo, final String layer, final String... references)
      throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = imageManifest(layer);

    for (final var reference : references) {
      final var response = this.wire.putImage(repo, IMAGE, reference, manifest);
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    }

    return manifest;
  }

  private MockHttpServletResponse tagsList(
      final Repo repo, final String image, final String query, final String authorization)
      throws Exception {

    final var request =
        get("/v2/{repo}/{image}/tags/list" + query, repo.getName(), image).with(protocolPort());

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse tagsList(final Repo repo, final String query) throws Exception {
    return this.tagsList(repo, IMAGE, query, this.adminToken);
  }

  private static List<String> tagsOf(final MockHttpServletResponse response) throws Exception {
    return JsonPath.read(response.getContentAsString(), "$.tags");
  }

  private String seedDeployToken(final Repo repo, final boolean readOnly) {
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

  /** What {@code docker login} and every client does: Basic credentials at /v2/token. */
  private String bearerFromTokenEndpoint(final Repo repo, final String secret) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .header(AUTHORIZATION, basicAuth("ignored", secret))
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  private void assertOciError(
      final MockHttpServletResponse response, final int status, final String code)
      throws Exception {

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
        .isEqualTo(code);
  }

  // ---------------------------------------------------------------------------------------------
  // The listing
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("lists the tags of the image in lexical order, under the repo-qualified name")
  void listsTheTags() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "v2", "latest", "v1.0", "alpha");

    final var response = this.tagsList(repo, "");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.name"))
        .isEqualTo(repo.getName() + "/" + IMAGE);
    assertThat(tagsOf(response)).containsExactly("alpha", "latest", "v1.0", "v2");
    assertThat(response.getHeader(LINK)).isNull();
  }

  @Test
  @DisplayName("lists a tag that points at an index, and only the tags of the asked image")
  void listsIndexTagsOfOneImage() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "layer-one", "child");
    final var indexJson = index(manifest);
    final var putIndex =
        this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, indexJson);
    assertThat(putIndex.getStatus()).as(putIndex.getContentAsString()).isEqualTo(201);
    this.wire.pushBlobsOf(repo, "other", "layer-two");
    assertThat(
            this.wire.putImage(repo, "other", "elsewhere", imageManifest("layer-two")).getStatus())
        .isEqualTo(201);

    assertThat(tagsOf(this.tagsList(repo, ""))).containsExactly("child", "multi");
    assertThat(tagsOf(this.tagsList(repo, "other", "", this.adminToken)))
        .containsExactly("elsewhere");
  }

  @Test
  @DisplayName("a moved tag is listed once, and a deleted tag is gone")
  void followsTagMovesAndDeletes() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "latest");
    this.push(repo, "layer-two", "latest", "keep");

    assertThat(tagsOf(this.tagsList(repo, ""))).containsExactly("keep", "latest");

    final var deleted =
        this.mockMvc
            .perform(
                delete("/v2/{repo}/{image}/manifests/{ref}", repo.getName(), IMAGE, "keep")
                    .header(AUTHORIZATION, this.adminToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(deleted.getStatus()).isEqualTo(202);

    assertThat(tagsOf(this.tagsList(repo, ""))).containsExactly("latest");
  }

  @Test
  @DisplayName("an image whose manifests are all untagged lists no tag, and is not unknown")
  void anUntaggedImageHasAnEmptyList() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "layer-one", sha256(bytes(imageManifest("layer-one"))));
    assertThat(manifest).isNotEmpty();

    final var response = this.tagsList(repo, "");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(tagsOf(response)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Pagination
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("n limits the page, and a Link names the next one until the tags are used up")
  void followingTheLinkVisitsEveryTagOnce() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1", "t2", "t3", "t4", "t5");

    final var seen = new ArrayList<String>();
    var uri = "/v2/%s/%s/tags/list?n=2".formatted(repo.getName(), IMAGE);
    var pages = 0;

    while (uri != null) {
      final var response =
          this.mockMvc
              .perform(get(uri).header(AUTHORIZATION, this.adminToken).with(protocolPort()))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      seen.addAll(tagsOf(response));
      pages++;

      final var link = response.getHeader(LINK);
      if (link == null) {
        uri = null;
      } else {
        final var matcher = NEXT_LINK.matcher(link);
        assertThat(matcher.matches()).as(link).isTrue();
        uri = matcher.group("url");
      }
    }

    assertThat(seen).containsExactly("t1", "t2", "t3", "t4", "t5");
    assertThat(pages).isEqualTo(3);
  }

  @Test
  @DisplayName("the Link of a page carries n and the last tag of the page")
  void theLinkNamesNAndLast() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1", "t2", "t3");

    final var response = this.tagsList(repo, "?n=2");

    assertThat(tagsOf(response)).containsExactly("t1", "t2");
    assertThat(response.getHeader(LINK))
        .isEqualTo(
            "</v2/%s/%s/tags/list?n=2&last=t2>; rel=\"next\"".formatted(repo.getName(), IMAGE));
  }

  @Test
  @DisplayName("last is exclusive, and a page that is exactly the rest has no Link")
  void lastIsExclusive() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1", "t2", "t3");

    final var response = this.tagsList(repo, "?n=2&last=t1");

    assertThat(tagsOf(response)).containsExactly("t2", "t3");
    assertThat(response.getHeader(LINK)).isNull();
    assertThat(tagsOf(this.tagsList(repo, "?last=t2"))).containsExactly("t3");
  }

  @Test
  @DisplayName("a last beyond the end is an empty list, not an error")
  void lastBeyondTheEnd() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1", "t2");

    final var response = this.tagsList(repo, "?n=5&last=zzz");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(tagsOf(response)).isEmpty();
    assertThat(response.getHeader(LINK)).isNull();
  }

  @Test
  @DisplayName("n=0 is an empty list with no Link")
  void zeroIsEmpty() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1", "t2");

    final var response = this.tagsList(repo, "?n=0");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(tagsOf(response)).isEmpty();
    assertThat(response.getHeader(LINK)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "-1", "", "1.5"})
  @DisplayName("an n that is not a non-negative integer is 400 PAGINATION_NUMBER_INVALID")
  void invalidN(final String n) throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "t1");

    this.assertOciError(this.tagsList(repo, "?n=" + n), 400, "PAGINATION_NUMBER_INVALID");
  }

  // ---------------------------------------------------------------------------------------------
  // Unknown names
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an image the repo does not have is 404 NAME_UNKNOWN")
  void unknownImage() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "latest");

    final var response = this.tagsList(repo, "nope", "", this.adminToken);

    this.assertOciError(response, 404, "NAME_UNKNOWN");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("imageNotFound");
  }

  @Test
  @DisplayName("a repo that does not exist is 404 NAME_UNKNOWN")
  void unknownRepo() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{image}/tags/list", uniqueRepoName("nosuch"), IMAGE)
                    .header(AUTHORIZATION, this.adminToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    this.assertOciError(response, 404, "NAME_UNKNOWN");
  }

  // ---------------------------------------------------------------------------------------------
  // Who may list
  // ---------------------------------------------------------------------------------------------

  /** What a client without credentials does: asks {@code /v2/token} for a pull token. */
  private String anonymousToken(final Repo repo) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  @Test
  @DisplayName("anonymous lists a public repo with the anonymous pull token, like a pull")
  void anonymousListsAPublicRepo() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "latest");

    final var response = this.tagsList(repo, IMAGE, "", this.anonymousToken(repo));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(tagsOf(response)).containsExactly("latest");
  }

  @Test
  @DisplayName("without any token even a public repo is challenged, as its manifest pull is")
  void aPublicRepoChallengesWithoutAToken() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, "layer-one", "latest");

    final var response = this.tagsList(repo, IMAGE, "", null);

    this.assertOciError(response, 401, "UNAUTHORIZED");
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .contains("scope=\"repository:%s/%s:pull\"".formatted(repo.getName(), IMAGE));
  }

  @Test
  @DisplayName("the token endpoint gives no anonymous pull token for a private repo")
  void noAnonymousTokenForAPrivateRepo() throws Exception {
    final var repo = this.privateDockerRepo();

    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("anonymous on a private repo is challenged with the pull scope, and learns nothing")
  void anonymousIsChallengedOnAPrivateRepo() throws Exception {
    final var repo = this.privateDockerRepo();
    this.push(repo, "layer-one", "latest");

    final var known = this.tagsList(repo, IMAGE, "", null);
    final var unknown = this.tagsList(repo, "nope", "", null);

    for (final var response : List.of(known, unknown)) {
      this.assertOciError(response, 401, "UNAUTHORIZED");
      assertThat(response.getHeader(WWW_AUTHENTICATE))
          .startsWith("Bearer realm=")
          .contains(
              "scope=\"repository:%s/%s:pull\""
                  .formatted(repo.getName(), response == known ? IMAGE : "nope"));
    }
  }

  @ParameterizedTest(name = "read-only={0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("a deploy token, read-only or not, lists a private repo, through /v2/token")
  void aDeployTokenLists(final boolean readOnly) throws Exception {
    final var repo = this.privateDockerRepo();
    this.push(repo, "layer-one", "latest", "v1");
    final var secret = this.seedDeployToken(repo, readOnly);
    final var token = this.bearerFromTokenEndpoint(repo, secret);

    final var response = this.tagsList(repo, IMAGE, "", token);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(tagsOf(response)).containsExactly("latest", "v1");
  }

  @Test
  @DisplayName("a deploy token of another repo cannot list a private repo")
  void aDeployTokenOfAnotherRepoCannotList() throws Exception {
    final var repo = this.privateDockerRepo();
    this.push(repo, "layer-one", "latest");
    final var other = this.privateDockerRepo();
    final var token = this.bearerFromTokenEndpoint(other, this.seedDeployToken(other, true));

    final var response = this.tagsList(repo, IMAGE, "", token);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).doesNotContain("latest");
  }
}
