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
package io.repsy.os.server.security.scan.controllers;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-2230: with scanning disabled (the default), every push of a gem used to leave a FAILED scan
 * "Artifact not found in storage", because the scan looked the gem up under the request path
 * ({@code api/v1/gems}) instead of its stored path, although the disabled scanner reads nothing.
 *
 * <p>Not {@code @Transactional}, like the sibling scan ITs: the scan runs on another thread and
 * must see the committed scan row. Fixtures are committed and removed in {@link #cleanUp()}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Ruby push, scanner disabled")
class RubyPushScanStateIT extends AbstractIT {

  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdRepoIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    this.repoRepository.deleteAllById(this.createdRepoIds);
    this.userRepository.deleteAllById(this.createdUserIds);
  }

  @Test
  @DisplayName("a pushed gem records a scan that completes, never a FAILED one")
  void pushedGemRecordsNoFailedScan() throws Exception {
    final var userInfo =
        this.userTxService.create("rubyscan" + randomTag(), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());
    final var user = this.userRepository.findById(userInfo.getId()).orElseThrow();
    final var repoName = uniqueRepoName("rubyscan");
    final var repoInfo = this.repoTxService.createRepo(repoName, RepoType.RUBY, true, null);
    this.createdRepoIds.add(repoInfo.getId());

    this.mockMvc
        .perform(
            post(PUBLISH_PATH, repoName)
                .with(protocolPort())
                .header(AUTHORIZATION, this.protocolBearerTokenFor(user))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem("scanned-gem", "1.0.0")))
        .andExpect(status().isOk());

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              final var scans =
                  this.scanRepository.findAllByRepoIdOrderByCreatedAtDesc(repoInfo.getId());
              assertThat(scans).isNotEmpty();
              assertThat(scans)
                  .extracting(VulnerabilityScan::getStatus)
                  .doesNotContain(ScanStatus.PENDING, ScanStatus.RUNNING, ScanStatus.FAILED);
            });
  }
}
