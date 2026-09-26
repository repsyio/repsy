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
package io.repsy.os.server.shared.multipart;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Base of the suites that push a package through a real Tomcat, because the multipart size limits
 * are enforced by the servlet container and {@code MockMvc} never applies them (RPS-1049).
 *
 * <p>The context listens on two ports found free at startup, one for the repository protocols and
 * one for the panel API. Each concrete class registers its own pair (see {@link
 * #registerPorts(DynamicPropertyRegistry, int, int)}): the Spring test cache keeps every context
 * alive, so two contexts that shared a pair would fight over it.
 *
 * <p>The pushes go to {@code POST /{repo}/api/charts}, the Helm chart upload. It stands in for the
 * PyPI and NuGet uploads because all three are multipart requests that read the package from one
 * file part, so one limit covers them, and a chart archive is the simplest one to build.
 *
 * <p>Runs without a test transaction (the server handles the request on its own thread, so it
 * cannot see rows an open transaction holds) and deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
abstract class AbstractMultipartLimitIT extends AbstractIntegrationTest {

  /** Seed of the noise in the archive, so a run always uploads the same bytes. */
  private static final long SEED = 1049L;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmStorageService helmStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /**
   * Sets the two listening ports of the context; the caller passes ports from {@link #freePort}.
   */
  protected static void registerPorts(
      final DynamicPropertyRegistry registry, final int protocolPort, final int apiPort) {
    registry.add("server.port", () -> protocolPort);
    registry.add("multiport.ports.api", () -> apiPort);
  }

  protected static int freePort() {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the charts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /** Pushes a chart archive of about {@code archiveBytes} and returns the HTTP status. */
  protected int pushChart(final int protocolPort, final int archiveBytes) {
    final var name = uniqueRepoName("mp-limit");
    final var repo = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(repo.getId());
    this.helmStorageService.createRepo(repo.getId());

    final var userInfo =
        this.userTxService.create(uniqueUsername("mp-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());
    final var token =
        this.protocolBearerTokenFor(this.userRepository.findById(userInfo.getId()).orElseThrow());

    final var archive = chartArchive("limit-chart", archiveBytes);
    final var body = new LinkedMultiValueMap<String, Object>();
    body.add(
        "chart",
        new ByteArrayResource(archive) {
          @Override
          public String getFilename() {
            return "limit-chart-1.0.0.tgz";
          }
        });

    return RestClient.create()
        .post()
        .uri("http://localhost:%d/%s/api/charts".formatted(protocolPort, name))
        .header(HttpHeaders.AUTHORIZATION, token)
        .body(body)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  /**
   * A chart archive of roughly {@code archiveBytes}: a valid {@code Chart.yaml} plus a file of
   * pseudo-random bytes, which gzip cannot compress.
   */
  private static byte[] chartArchive(final String chartName, final int archiveBytes) {
    final var noise = new byte[archiveBytes];
    new Random(SEED).nextBytes(noise);

    try {
      final var out = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(out);
          final var tar = new TarArchiveOutputStream(gzip)) {
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        addEntry(
            tar,
            chartName + "/Chart.yaml",
            "apiVersion: v2\nname: %s\nversion: 1.0.0\n"
                .formatted(chartName)
                .getBytes(StandardCharsets.UTF_8));
        addEntry(tar, chartName + "/files/noise.bin", noise);
      }
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void addEntry(
      final TarArchiveOutputStream tar, final String path, final byte[] data) throws IOException {
    final var entry = new TarArchiveEntry(path);
    entry.setSize(data.length);
    tar.putArchiveEntry(entry);
    tar.write(data);
    tar.closeArchiveEntry();
  }
}
