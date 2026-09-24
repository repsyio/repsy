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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("The size and digest an image is listed with follow its tags (RPS-1318)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DockerImageSizeRefreshIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String panelToken;
  private String protocolToken;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(
            uniqueUsername("imgsize"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.panelToken = this.bearerTokenFor(user.getId(), user.getUsername());
    this.protocolToken = this.protocolBearerTokenFor(user);
    this.wire =
        new DockerWire(
            this.mockMvc, this.webApplicationContext, protocolPort(), this.protocolToken);
  }

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-size");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String push(final Repo repo, final String reference, final String layer)
      throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = imageManifest(layer);
    final var response = this.wire.putImage(repo, IMAGE, reference, manifest);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);

    return sha256(bytes(manifest));
  }

  /** The bytes of the config blob and the layer of the manifest {@code imageManifest(layer)}. */
  private long sizeOf(final Repo repo, final String layer) {
    final var configDigest = JsonPath.<String>read(imageManifest(layer), "$.config.digest");
    final var config =
        this.jdbcTemplate.queryForObject(
            "select size from docker_layer where repo_id = ? and digest = ?",
            Long.class,
            repo.getId(),
            configDigest);
    assertThat(config).isNotNull().isPositive();

    return config + bytes(layer).length;
  }

  private long storedSize(final Repo repo) {
    final var size =
        this.jdbcTemplate.queryForObject(
            "select size from docker_image where repo_id = ? and name = ?",
            Long.class,
            repo.getId(),
            IMAGE);

    return size == null ? 0 : size;
  }

  private String storedDigest(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select digest from docker_image where repo_id = ? and name = ?",
        String.class,
        repo.getId(),
        IMAGE);
  }

  private void deletePanelTag(final Repo repo, final String tag) throws Exception {
    this.expectSuccess(
        this.perform(
            delete("/api/docker/images/%s/%s/tags/%s".formatted(repo.getName(), IMAGE, tag))
                .header(AUTHORIZATION, this.panelToken)),
        "tagDeleted",
        "Tag deleted.");
  }

  private int deleteOnWire(final Repo repo, final String reference) throws Exception {
    return this.mockMvc
        .perform(
            delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header(AUTHORIZATION, this.protocolToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("deleting a tag in the panel recomputes the size and the digest in its transaction")
  void aPanelTagDeleteRecomputes() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, "v1", "layer-one");
    this.push(repo, "v2", "layer-two");

    this.deletePanelTag(repo, "v2");

    assertThat(this.storedSize(repo)).isEqualTo(this.sizeOf(repo, "layer-one"));
    assertThat(this.storedDigest(repo)).isEqualTo(first);

    this.deletePanelTag(repo, "v1");

    assertThat(this.storedSize(repo)).isZero();
    assertThat(this.storedDigest(repo)).isNull();
  }

  @Test
  @DisplayName("deleting a tag on the wire recomputes the size and the digest")
  void aWireTagDeleteRecomputes() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, "v1", "layer-one");
    this.push(repo, "v2", "layer-two");

    assertThat(this.deleteOnWire(repo, "v2")).isEqualTo(202);

    assertThat(this.storedSize(repo)).isEqualTo(this.sizeOf(repo, "layer-one"));
    assertThat(this.storedDigest(repo)).isEqualTo(first);
  }

  @Test
  @DisplayName("deleting a manifest by digest on the wire recomputes the size and the digest")
  void aWireManifestDeleteRecomputes() throws Exception {
    final var repo = this.dockerRepo();
    final var first = this.push(repo, "v1", "layer-one");
    final var second = this.push(repo, "v2", "layer-two");

    assertThat(this.deleteOnWire(repo, second)).isEqualTo(202);

    assertThat(this.storedSize(repo)).isEqualTo(this.sizeOf(repo, "layer-one"));
    assertThat(this.storedDigest(repo)).isEqualTo(first);
  }

  @Test
  @DisplayName("an index push sets the size, and deleting the index by digest zeroes it again")
  void anIndexDeleteRecomputes() throws Exception {
    final var repo = this.dockerRepo();
    final var child = this.push(repo, sha256(bytes(imageManifest("layer-child"))), "layer-child");
    final var indexJson = index(imageManifest("layer-child"));
    assertThat(
            this.wire
                .putManifest(repo, IMAGE, "multi", DockerWire.OCI_INDEX, indexJson)
                .getStatus())
        .isEqualTo(201);
    final var indexDigest = sha256(bytes(indexJson));
    assertThat(this.storedDigest(repo)).isEqualTo(indexDigest);
    assertThat(this.storedSize(repo)).isEqualTo(this.sizeOf(repo, "layer-child"));
    assertThat(child).isNotEqualTo(indexDigest);

    assertThat(this.deleteOnWire(repo, indexDigest)).isEqualTo(202);

    assertThat(this.storedSize(repo)).as("the child is untagged now").isZero();
    assertThat(this.storedDigest(repo)).isNull();
  }
}
