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
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1216: {@code DELETE /v2/<name>/manifests/<reference>}, over the real wire protocol. A digest
 * (of either algorithm) deletes the manifest and the tags that point at it, a tag deletes the tag
 * only, and both need MANAGE, which a deploy token never has.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data, and the mock records the disk usage the pushes and deletes ask for.
 */
@RecordApplicationEvents
@DisplayName("Docker protocol DELETE of manifests and tags (RPS-1216)")
class DockerManifestDeleteIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private ManifestChildRepository manifestChildRepository;
  @Autowired private TagRepository tagRepository;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private ApplicationEvents applicationEvents;
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

  /** Pushes an image manifest built from {@code layer} and answers its JSON. */
  private String push(
      final Repo repo, final String image, final String reference, final String layer)
      throws Exception {
    this.wire.pushBlobsOf(repo, image, layer);
    final var manifest = imageManifest(layer);
    final var response = this.wire.putImage(repo, image, reference, manifest);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return manifest;
  }

  private MockHttpServletResponse deleteReference(
      final Repo repo, final String image, final String reference, final String authorization)
      throws Exception {

    final var request =
        delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), image, reference)
            .with(protocolPort());

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse deleteReference(
      final Repo repo, final String image, final String reference) throws Exception {
    return this.deleteReference(repo, image, reference, this.adminToken);
  }

  private long netUsage(final Repo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  private boolean manifestFileExists(final Repo repo, final String digest) {
    return Files.exists(storageDirOf(repo).resolve("manifests").resolve(digest));
  }

  private List<String> deletedVersions() {
    return this.applicationEvents.stream(ArtifactVersionDeletedEvent.class)
        .map(ArtifactVersionDeletedEvent::artifactVersion)
        .toList();
  }

  private void expectOciError(
      final MockHttpServletResponse response,
      final int status,
      final String code,
      final String detail)
      throws Exception {

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    final var body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo(code);
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo(detail);
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

  /**
   * What {@code docker login} and every client does: Basic credentials for a token at /v2/token.
   */
  private String bearerFromTokenEndpoint(final String username, final String password)
      throws Exception {

    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .header(AUTHORIZATION, basicAuth(username, password))
                    .param("scope", "repository:x/app:delete")
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

    return AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("deleting by digest removes the manifest and every tag that pointed at it")
  void deleteByDigestRemovesTheManifestAndItsTags(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    assertThat(this.wire.putImage(repo, IMAGE, "v1", manifest).getStatus()).isEqualTo(201);
    final var digest = sha256(bytes(manifest));
    final var reference = "sha512".equals(algorithm) ? sha512(bytes(manifest)) : digest;
    // Another manifest keeps the image: with none, the image goes with this one (RPS-1288) and
    // every
    // read below is NAME_UNKNOWN instead (see the tests of the last manifest).
    this.push(repo, IMAGE, "other", "layer-two");
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    clearInvocations(this.usageUpdateService);

    final var response = this.deleteReference(repo, IMAGE, reference);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    assertThat(response.getContentAsString()).isEmpty();
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, digest), 404, "MANIFEST_UNKNOWN", "manifestNotFound");
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, sha512(bytes(manifest))),
        404,
        "MANIFEST_UNKNOWN",
        "manifestNotFound");
    assertThat(this.wire.headManifest(repo, IMAGE, digest).getStatus()).isEqualTo(404);
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, "latest"), 404, "MANIFEST_UNKNOWN", "tagNotFound");
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, "v1"), 404, "MANIFEST_UNKNOWN", "tagNotFound");
    assertThat(this.manifestRepository.findAllByImageId(image.getId()))
        .extracting(row -> row.getDigest())
        .containsExactly(sha256(bytes(imageManifest("layer-two"))));
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .extracting(tag -> tag.getName())
        .containsExactly("other");
    assertThat(this.manifestFileExists(repo, digest)).isFalse();
    assertThat(this.netUsage(repo)).isEqualTo(-bytes(manifest).length);
    assertThat(this.deletedVersions()).containsExactlyInAnyOrder("latest", "v1");
  }

  @Test
  @DisplayName("deleting an untagged manifest by digest publishes no tag event")
  void deletingAnUntaggedManifestPublishesNoEvent() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    this.push(repo, IMAGE, "latest", "layer-two");
    final var oldDigest = sha256(bytes(manifest));
    assertThat(this.wire.getManifest(repo, IMAGE, oldDigest).getStatus())
        .as("untagged after the override, still pullable")
        .isEqualTo(200);

    final var response = this.deleteReference(repo, IMAGE, oldDigest);

    assertThat(response.getStatus()).isEqualTo(202);
    assertThat(this.wire.getManifest(repo, IMAGE, oldDigest).getStatus()).isEqualTo(404);
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus())
        .as("the tag points at the other manifest and is not touched")
        .isEqualTo(200);
    assertThat(this.deletedVersions()).isEmpty();
  }

  @Test
  @DisplayName("deleting by tag removes the tag only: the manifest stays pullable by digest")
  void deleteByTagRemovesOnlyTheTag() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    assertThat(this.wire.putImage(repo, IMAGE, "v1", manifest).getStatus()).isEqualTo(201);
    final var digest = sha256(bytes(manifest));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    clearInvocations(this.usageUpdateService);

    final var response = this.deleteReference(repo, IMAGE, "latest");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    assertThat(response.getContentAsString()).isEmpty();
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, "latest"), 404, "MANIFEST_UNKNOWN", "tagNotFound");
    assertThat(this.wire.getManifest(repo, IMAGE, "v1").getContentAsString())
        .as("another tag of the same manifest is not affected")
        .isEqualTo(manifest);
    final var byDigest = this.wire.getManifest(repo, IMAGE, digest);
    assertThat(byDigest.getStatus()).isEqualTo(200);
    assertThat(byDigest.getContentAsString()).isEqualTo(manifest);
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(1);
    assertThat(this.manifestFileExists(repo, digest)).isTrue();
    assertThat(this.netUsage(repo)).isZero();
    assertThat(this.deletedVersions()).containsExactly("latest");
  }

  @Test
  @DisplayName("a digest reference is not a tag: deleting a tag by name never deletes a manifest")
  void deletingTheLastTagLeavesAnUntaggedManifestThatADigestDeleteThenRemoves() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var digest = sha256(bytes(manifest));

    assertThat(this.deleteReference(repo, IMAGE, "latest").getStatus()).isEqualTo(202);
    assertThat(this.deleteReference(repo, IMAGE, "latest").getStatus())
        .as("the tag is gone")
        .isEqualTo(404);
    assertThat(this.wire.getManifest(repo, IMAGE, digest).getStatus()).isEqualTo(200);
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE))
        .as("deleting the last tag never removes the image (RPS-1288)")
        .isPresent();

    assertThat(this.deleteReference(repo, IMAGE, digest).getStatus()).isEqualTo(202);
    this.expectOciError(
        this.wire.getManifest(repo, IMAGE, digest), 404, "NAME_UNKNOWN", "imageNotFound");
    assertThat(this.manifestFileExists(repo, digest)).isFalse();
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE))
        .as("the last manifest took the image with it (RPS-1288)")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "an image whose last manifest was deleted by digest is created again by the next push")
  void anImageRemovedWithItsLastManifestIsCreatedAgainByAPush() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    assertThat(this.deleteReference(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(202);
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE)).isEmpty();

    this.push(repo, IMAGE, "v2", "layer-two");

    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .extracting(tag -> tag.getName())
        .containsExactly("v2");
    assertThat(this.wire.getManifest(repo, IMAGE, "v2").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("deleting one of two manifests by digest keeps the image")
  void deletingOneOfTwoManifestsKeepsTheImage() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, IMAGE, "v1", "layer-one");
    final var second = this.push(repo, IMAGE, "v2", "layer-two");

    assertThat(this.deleteReference(repo, IMAGE, sha256(bytes(first))).getStatus()).isEqualTo(202);

    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE)).isPresent();
    assertThat(this.wire.getManifest(repo, IMAGE, "v2").getContentAsString()).isEqualTo(second);
  }

  @Test
  @DisplayName("an index and its children: the image goes with the last of them, not before")
  void anImageWithAnIndexGoesWithItsLastManifest() throws Exception {
    final var repo = this.dockerRepo();
    final var one = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-one"))), "layer-one");
    final var list = index(one);
    assertThat(this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, list).getStatus())
        .isEqualTo(201);

    assertThat(this.deleteReference(repo, IMAGE, sha256(bytes(list))).getStatus()).isEqualTo(202);
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE))
        .as("its child manifest is still stored")
        .isPresent();

    assertThat(this.deleteReference(repo, IMAGE, sha256(bytes(one))).getStatus()).isEqualTo(202);
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE)).isEmpty();
  }

  @Test
  @DisplayName("a deleted manifest can be pushed again")
  void aDeletedManifestCanBePushedAgain() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var digest = sha256(bytes(manifest));
    assertThat(this.deleteReference(repo, IMAGE, digest).getStatus()).isEqualTo(202);

    final var again = this.wire.putImage(repo, IMAGE, "latest", manifest);

    assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(201);
    assertThat(this.wire.getManifest(repo, IMAGE, digest).getContentAsString()).isEqualTo(manifest);
    assertThat(this.manifestFileExists(repo, digest)).isTrue();
  }

  @Test
  @DisplayName("deleting an index by digest keeps the manifests it listed, untagged")
  void deletingAnIndexKeepsItsChildren() throws Exception {
    final var repo = this.dockerRepo();
    final var one = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-one"))), "layer-one");
    final var two = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-two"))), "layer-two");
    final var list = index(one, two);
    assertThat(this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, list).getStatus())
        .isEqualTo(201);
    final var listDigest = sha256(bytes(list));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var indexRow =
        this.manifestRepository.findByImageIdAndDigest(image.getId(), listDigest).orElseThrow();
    assertThat(this.manifestChildRepository.findAllByParentId(indexRow.getId())).hasSize(2);
    this.entityManager.flush();
    this.entityManager.clear();
    clearInvocations(this.usageUpdateService);

    final var response = this.deleteReference(repo, IMAGE, listDigest);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(this.wire.getManifest(repo, IMAGE, listDigest).getStatus()).isEqualTo(404);
    assertThat(this.wire.getManifest(repo, IMAGE, "multi").getStatus()).isEqualTo(404);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(one))).getContentAsString())
        .isEqualTo(one);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(two))).getContentAsString())
        .isEqualTo(two);
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(2);
    assertThat(this.manifestChildRepository.findAllByParentId(indexRow.getId())).isEmpty();
    assertThat(this.manifestFileExists(repo, listDigest)).isFalse();
    assertThat(this.manifestFileExists(repo, sha256(bytes(one)))).isTrue();
    assertThat(this.manifestFileExists(repo, sha256(bytes(two)))).isTrue();
    assertThat(this.netUsage(repo)).isEqualTo(-bytes(list).length);
    assertThat(this.deletedVersions()).containsExactly("multi");
  }

  @Test
  @DisplayName("deleting a manifest an index lists leaves the index, without that edge")
  void deletingAChildLeavesTheIndex() throws Exception {
    final var repo = this.dockerRepo();
    final var one = this.push(repo, IMAGE, sha256(bytes(imageManifest("layer-one"))), "layer-one");
    final var list = index(one);
    assertThat(this.wire.putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, list).getStatus())
        .isEqualTo(201);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var indexRow =
        this.manifestRepository
            .findByImageIdAndDigest(image.getId(), sha256(bytes(list)))
            .orElseThrow();
    this.entityManager.flush();
    this.entityManager.clear();

    final var response = this.deleteReference(repo, IMAGE, sha256(bytes(one)));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(this.wire.getManifest(repo, IMAGE, "multi").getContentAsString()).isEqualTo(list);
    assertThat(this.manifestChildRepository.findAllByParentId(indexRow.getId())).isEmpty();
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(one))).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a file shared by two images goes only with the last manifest that has its digest")
  void sharedFileIsKeptUntilTheLastRowGoes() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "first", "latest", "layer-one");
    this.wire.pushBlobsOf(repo, "second", "layer-one");
    assertThat(this.wire.putImage(repo, "second", "latest", manifest).getStatus()).isEqualTo(201);
    final var digest = sha256(bytes(manifest));
    clearInvocations(this.usageUpdateService);

    assertThat(this.deleteReference(repo, "first", digest).getStatus()).isEqualTo(202);

    assertThat(this.manifestFileExists(repo, digest)).as("the other image needs it").isTrue();
    assertThat(this.netUsage(repo)).as("nothing is freed yet").isZero();
    assertThat(this.wire.getManifest(repo, "first", digest).getStatus()).isEqualTo(404);
    assertThat(this.wire.getManifest(repo, "second", "latest").getContentAsString())
        .isEqualTo(manifest);

    assertThat(this.deleteReference(repo, "second", digest).getStatus()).isEqualTo(202);

    assertThat(this.manifestFileExists(repo, digest)).isFalse();
    assertThat(this.netUsage(repo)).isEqualTo(-bytes(manifest).length);
  }

  @Test
  @DisplayName("an unknown digest of either algorithm is 404 MANIFEST_UNKNOWN, also the 2nd time")
  void unknownDigestIsNotFound() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var other = imageManifest("layer-two");

    this.expectOciError(
        this.deleteReference(repo, IMAGE, sha256(bytes(other))),
        404,
        "MANIFEST_UNKNOWN",
        "manifestNotFound");
    this.expectOciError(
        this.deleteReference(repo, IMAGE, sha512(bytes(other))),
        404,
        "MANIFEST_UNKNOWN",
        "manifestNotFound");
    assertThat(this.deleteReference(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(202);
    // It was the image's last manifest, so the image is gone with it (RPS-1288).
    this.expectOciError(
        this.deleteReference(repo, IMAGE, sha256(bytes(manifest))),
        404,
        "NAME_UNKNOWN",
        "imageNotFound");
  }

  @Test
  @DisplayName("an unknown tag is 404 MANIFEST_UNKNOWN, an unknown image 404 NAME_UNKNOWN")
  void unknownTagAndImageAreNotFound() throws Exception {
    final var repo = this.dockerRepo();
    this.push(repo, IMAGE, "latest", "layer-one");

    this.expectOciError(
        this.deleteReference(repo, IMAGE, "nope"), 404, "MANIFEST_UNKNOWN", "tagNotFound");
    this.expectOciError(
        this.deleteReference(repo, "ghost", "latest"), 404, "NAME_UNKNOWN", "imageNotFound");
    this.expectOciError(
        this.deleteReference(repo, "ghost", sha256(bytes("x"))),
        404,
        "NAME_UNKNOWN",
        "imageNotFound");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a repo that is not a Docker repo has no such route")
  void aNonDockerRepoIsNotFound() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("maven"));

    final var response = this.deleteReference(repo, IMAGE, "latest");

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-bad", "sha256:short", "sha384:abc"})
  @DisplayName("a reference that is neither a tag nor a supported digest is 400")
  void malformedReferenceIsBadRequest(final String reference) throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");

    final var response = this.deleteReference(repo, IMAGE, reference);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
        .isIn("TAG_INVALID", "DIGEST_INVALID");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
  }

  @Test
  @DisplayName("without credentials the request is challenged, and nothing is deleted")
  void anonymousIsChallenged() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");

    final var response = this.deleteReference(repo, IMAGE, sha256(bytes(manifest)), null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Bearer realm=");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
        .isEqualTo("UNAUTHORIZED");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus())
        .as("even a public repo cannot be deleted from anonymously")
        .isEqualTo(200);
  }

  @Test
  @DisplayName("a user who is not an admin can read and write, but not delete")
  void aPlainUserCannotDelete() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var user = this.createUser(uniqueUsername("plain"), UserRole.USER);
    final var token = this.protocolBearerTokenFor(user);

    final var byDigest = this.deleteReference(repo, IMAGE, sha256(bytes(manifest)), token);
    final var byTag = this.deleteReference(repo, IMAGE, "latest", token);

    assertThat(byDigest.getStatus()).isEqualTo(401);
    assertThat(byTag.getStatus()).isEqualTo(401);
    assertThat(byTag.getHeader(WWW_AUTHENTICATE)).startsWith("Bearer realm=");
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @ParameterizedTest(name = "read-only={0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("a deploy token, even one that may write, cannot delete")
  void aDeployTokenCannotDelete(final boolean readOnly) throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var secret = this.seedDeployToken(repo, readOnly);
    final var token = this.bearerFromTokenEndpoint("ignored", secret);

    final var byDigest = this.deleteReference(repo, IMAGE, sha256(bytes(manifest)), token);
    final var byTag = this.deleteReference(repo, IMAGE, "latest", token);

    assertThat(byDigest.getStatus()).isEqualTo(401);
    assertThat(byTag.getStatus()).isEqualTo(401);
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(repo, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("the flow of a real client: Basic credentials for a token at /v2/token, then DELETE")
  void tokenFlowOfAnAdmin() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var admin = this.createUser(uniqueUsername("admin"), UserRole.ADMIN);
    final var token = this.bearerFromTokenEndpoint(admin.getUsername(), VALID_PASSWORD);

    final var response = this.deleteReference(repo, IMAGE, sha256(bytes(manifest)), token);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    assertThat(this.wire.getManifest(repo, IMAGE, "latest").getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a plain user and a deploy token can still pull and push")
  void otherPermissionsAreUntouched() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, IMAGE, "latest", "layer-one");
    final var secret = this.seedDeployToken(repo, false);
    final var token = this.bearerFromTokenEndpoint("ignored", secret);
    final var wire =
        new DockerWire(this.mockMvc, this.webApplicationContext, protocolPort(), token);

    assertThat(wire.getManifest(repo, IMAGE, "latest").getContentAsString()).isEqualTo(manifest);
    assertThat(wire.putImage(repo, IMAGE, "other", manifest).getStatus()).isEqualTo(201);
  }
}
