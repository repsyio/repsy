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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
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
 * The registry rules of the Docker server over the real wire protocol that no other IT pins in one
 * place: the OCI error codes of a refused manifest push, the orphan blob a refused override leaves
 * behind (blobs go up before the manifest, so the refused push's own blobs stay), the idempotent
 * re-push of identical bytes when override is off, and a Basic {@code Authorization} header on
 * {@code DELETE}, which is refused with the delete-scope Bearer challenge.
 */
@DisplayName("Docker registry rules (wire level)")
class DockerRegistryRulesIT extends AbstractIT {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
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

  private Repo withOverrideDisabled(final Repo repo) {
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();

    managed.setAllowOverride(false);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  private String push(final Repo repo, final String tag, final String layer) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = imageManifest(layer);
    final var response = this.wire.putImage(repo, IMAGE, tag, manifest);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return manifest;
  }

  private static void assertOciError(
      final MockHttpServletResponse response, final int status, final String code)
      throws Exception {

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
        .isEqualTo(code);
  }

  private int headBlobStatus(final Repo repo, final String digest) throws Exception {
    return this.mockMvc
        .perform(
            head("/v2/{repo}/{image}/blobs/{digest}", repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, this.adminToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName(
      "a manifest that references blobs the repo does not hold is 404 MANIFEST_BLOB_UNKNOWN")
  void unknownBlobIsRefused() throws Exception {
    final var repo = this.dockerRepo();

    assertOciError(
        this.wire.putImage(repo, IMAGE, "tag1", imageManifest("never-uploaded")),
        404,
        "MANIFEST_BLOB_UNKNOWN");
  }

  @Test
  @DisplayName(
      "a wrong sha256 reference is 400 DIGEST_INVALID, an unknown Content-Type 400 MANIFEST_INVALID")
  void wrongReferenceAndUnknownContentTypeAreRefused() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer");
    final var manifest = imageManifest("layer");

    assertOciError(
        this.wire.putImage(repo, IMAGE, "sha256:" + "0".repeat(64), manifest),
        400,
        "DIGEST_INVALID");
    assertOciError(
        this.wire.putManifest(repo, IMAGE, "tag1", "text/plain", manifest),
        400,
        "MANIFEST_INVALID");
  }

  @Test
  @DisplayName(
      "a refused override is 403 DENIED, keeps the tag and leaves the refused push's blob stored")
  void refusedOverrideLeavesAnOrphanBlob() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, "tag1", "v1");
    final var disabled = this.withOverrideDisabled(repo);
    this.wire.pushBlobsOf(disabled, IMAGE, "v2");

    final var refused = this.wire.putImage(disabled, IMAGE, "tag1", imageManifest("v2"));

    assertOciError(refused, 403, "DENIED");
    assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("packageOverrideDisabled");
    assertThat(this.wire.getManifest(disabled, IMAGE, "tag1").getContentAsString())
        .isEqualTo(first);
    assertThat(this.headBlobStatus(disabled, sha256(bytes("v2"))))
        .as("the refused push's blob is stored anyway, blobs go up before the manifest")
        .isEqualTo(200);
  }

  @Test
  @DisplayName("re-pushing identical bytes to a tag is accepted when override is off")
  void identicalRepushIsIdempotentWhenOverrideIsOff() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "tag1", "v1");
    final var disabled = this.withOverrideDisabled(repo);

    final var again = this.wire.putImage(disabled, IMAGE, "tag1", manifest);

    assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(201);
    assertThat(this.wire.getManifest(disabled, IMAGE, "tag1").getContentAsString())
        .isEqualTo(manifest);
    assertThat(this.wire.getManifest(disabled, IMAGE, sha256(bytes(manifest))).getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName(
      "DELETE with a Basic Authorization header is 401 with the delete-scope Bearer challenge")
  void basicAuthOnDeleteIsRefused() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.push(repo, "tag-basic", "v1");
    final var admin = this.createUser(uniqueUsername("admin"), UserRole.ADMIN);
    final var digest = sha256(bytes(manifest));

    final var response =
        this.mockMvc
            .perform(
                delete("/v2/{repo}/{image}/manifests/{digest}", repo.getName(), IMAGE, digest)
                    .header(AUTHORIZATION, basicAuth(admin.getUsername(), VALID_PASSWORD))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .startsWith("Bearer realm=")
        .contains("scope=\"repository:%s/%s:delete\"".formatted(repo.getName(), IMAGE));
    assertThat(this.wire.getManifest(repo, IMAGE, digest).getStatus())
        .as("nothing was deleted by the refused request")
        .isEqualTo(200);
  }
}
