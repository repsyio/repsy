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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1448: a delete whose manifest file cannot be deleted leaves everything as it was.
 *
 * <p>The delete publishes the version-deleted event (which removes the vulnerability scans of the
 * version) before it deletes the manifest files. That order is safe because the facade, the
 * manifest deletion and the scan cleanup all run in one transaction: the listener joins it, so when
 * the file delete fails the scan rows come back with the image, the tags and the manifests. These
 * tests fail the file delete and pin that, and that the usage counter is only touched once the
 * delete succeeded.
 *
 * <p>Runs without a test transaction, since a rollback is only observable when the request's own
 * transaction rolls back for real; it deletes the repos (the scans go with their repo) and the user
 * it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Docker delete when the manifest file cannot be deleted (RPS-1448)")
class DockerDeleteStorageFailureIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String TAG = "latest";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private VulnerabilityScanRepository scanRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private DockerStorageService dockerStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String protocolToken;
  private String panelToken;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(
            uniqueUsername("deletefail"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.protocolToken = this.protocolBearerTokenFor(user);
    this.panelToken = this.bearerTokenFor(user);
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
    final var name = uniqueRepoName("docker-delfail");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  /** Pushes an image with one tag and gives that version a completed scan; returns the scan id. */
  private UUID pushScannedImage(final Repo repo, final String manifest) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    assertThat(this.wire.putImage(repo, IMAGE, TAG, manifest).getStatus()).isEqualTo(201);

    final var scan = new VulnerabilityScan();
    scan.setRepo(repo);
    scan.setArtifactName(IMAGE);
    scan.setArtifactVersion(TAG);
    scan.setStatus(ScanStatus.COMPLETED);
    scan.setScannerName("trivy");

    return this.scanRepository.saveAndFlush(scan).getId();
  }

  private void failManifestFileDeletes() {
    doThrow(new UncheckedIOException(new IOException("disk gone")))
        .when(this.dockerStorageService)
        .deleteManifests(any(), any());
  }

  private MockHttpServletResponse deleteImage(final Repo repo) throws Exception {
    return this.perform(
            delete("/api/docker/images/{repo}/{image}", repo.getName(), IMAGE)
                .header(AUTHORIZATION, this.panelToken))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse deleteManifestByDigest(final Repo repo, final String digest)
      throws Exception {

    return this.mockMvc
        .perform(
            delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, this.protocolToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private int count(final String sql, final Object... args) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, args);

    return count == null ? 0 : count;
  }

  private int imageRows(final Repo repo) {
    return this.count("select count(*) from docker_image where repo_id = ?", repo.getId());
  }

  private int tagRows(final Repo repo) {
    return this.count(
        """
        select count(*) from docker_tag t join docker_image i on i.id = t.image_id
        where i.repo_id = ?
        """,
        repo.getId());
  }

  private int manifestRows(final Repo repo) {
    return this.count(
        """
        select count(*) from docker_manifest m join docker_image i on i.id = m.image_id
        where i.repo_id = ?
        """,
        repo.getId());
  }

  private int scanRows(final UUID scanId) {
    return this.count("select count(*) from vulnerability_scan where id = ?", scanId);
  }

  private long manifestFiles(final Repo repo) throws IOException {
    try (var files = Files.list(storageDirOf(repo).resolve("manifests"))) {
      return files.count();
    }
  }

  @Test
  @DisplayName("deleting an image whose manifest file cannot be deleted keeps its rows and scans")
  void aFailedImageDeleteKeepsTheImageAndItsScans() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = imageManifest("layer-one");
    final var scanId = this.pushScannedImage(repo, manifest);
    Mockito.reset(this.usageUpdateService); // what the pushes charged is not under test
    this.failManifestFileDeletes();

    final var failed = this.deleteImage(repo);

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isEqualTo(500);
    assertThat(this.imageRows(repo)).as("the image row came back").isEqualTo(1);
    assertThat(this.tagRows(repo)).isEqualTo(1);
    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.scanRows(scanId))
        .as("the scan cleanup joined the transaction that rolled back")
        .isEqualTo(1);
    assertThat(this.manifestFiles(repo)).as("the manifest file was not deleted").isEqualTo(1);
    verify(this.usageUpdateService, never()).updateUsage(any());

    // Once the storage works again the same delete goes through, and only now do the scans go.
    Mockito.reset(this.dockerStorageService);
    final var deleted = this.deleteImage(repo);

    assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);
    assertThat(this.imageRows(repo)).isZero();
    assertThat(this.tagRows(repo)).isZero();
    assertThat(this.manifestRows(repo)).isZero();
    assertThat(this.scanRows(scanId)).isZero();
    assertThat(this.manifestFiles(repo)).isZero();
    final var usage = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService).updateUsage(usage.capture());
    assertThat(usage.getValue().repoId()).isEqualTo(repo.getId());
    assertThat(usage.getValue().usages().getDiskUsage())
        .as("the freed manifest bytes are refunded")
        .isEqualTo(-1L * bytes(manifest).length);
  }

  @Test
  @DisplayName("deleting the last manifest by digest whose file cannot be deleted keeps its scans")
  void aFailedManifestDeleteKeepsTheImageAndItsScans() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = imageManifest("layer-one");
    final var scanId = this.pushScannedImage(repo, manifest);
    this.failManifestFileDeletes();

    final var failed = this.deleteManifestByDigest(repo, sha256(bytes(manifest)));

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isEqualTo(500);
    assertThat(this.imageRows(repo)).as("the image the last manifest took came back").isEqualTo(1);
    assertThat(this.tagRows(repo)).isEqualTo(1);
    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.scanRows(scanId)).isEqualTo(1);
    assertThat(this.manifestFiles(repo)).isEqualTo(1);

    Mockito.reset(this.dockerStorageService);
    final var deleted = this.deleteManifestByDigest(repo, sha256(bytes(manifest)));

    assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(202);
    assertThat(this.imageRows(repo)).isZero();
    assertThat(this.scanRows(scanId)).isZero();
    assertThat(this.manifestFiles(repo)).isZero();
  }
}
