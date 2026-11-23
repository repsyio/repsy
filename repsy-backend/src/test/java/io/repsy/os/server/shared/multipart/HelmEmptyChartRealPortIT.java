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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * RPS-1466, RPS-2739: a classic Helm chart upload with an empty chart part, and one that is not
 * multipart at all ({@code curl --data-binary}), through a real Tomcat. {@code EmptyUploadBodyIT}
 * pins the handler on MockMvc; what only a real servlet container does is parse (or refuse to
 * parse) the multipart body before the handler runs, which was the {@code 500} of RPS-1466.
 */
@DisplayName("Helm chart upload with an empty or non-multipart body, real port (RPS-1466)")
class HelmEmptyChartRealPortIT extends AbstractMultipartLimitIT {

  private static final int PROTOCOL_PORT = freePort();
  private static final int API_PORT_OF_CONTEXT = freePort();

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmStorageService helmStorageService;

  private final List<UUID> repoIds = new ArrayList<>();
  private final List<UUID> userIds = new ArrayList<>();

  @DynamicPropertySource
  static void registerPortsOfThisContext(final DynamicPropertyRegistry registry) {
    registerPorts(registry, PROTOCOL_PORT, API_PORT_OF_CONTEXT);
  }

  @AfterEach
  void deleteOwnData() {
    this.repoIds.forEach(id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.repoIds.clear();
    this.deleteCommittedUsers(this.userIds);
    this.userIds.clear();
  }

  private record Answer(int status, String body) {}

  /** Creates a Helm repo and returns its name; the caller's token comes from {@link #token()}. */
  private String createHelmRepo() {
    final var name = uniqueRepoName("helm-empty");
    final var repo = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.repoIds.add(repo.getId());
    this.helmStorageService.createRepo(repo.getId());
    return name;
  }

  private String token() {
    final var info =
        this.userTxService.create(
            uniqueUsername("empty-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.userIds.add(info.getId());
    return this.protocolBearerTokenFor(this.userRepository.findById(info.getId()).orElseThrow());
  }

  private Answer post(final String repo, final MediaType type, final Object body) {
    return RestClient.create()
        .post()
        .uri("http://localhost:%d/%s/api/charts".formatted(PROTOCOL_PORT, repo))
        .header(HttpHeaders.AUTHORIZATION, this.token())
        .contentType(type)
        .body(body)
        .exchange(
            (request, response) ->
                new Answer(
                    response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes())));
  }

  @Test
  @DisplayName("an empty chart part answers 400 helmChartEmpty")
  void emptyChartPartIsRefused() {
    final var repo = this.createHelmRepo();
    final var parts = new LinkedMultiValueMap<String, Object>();
    parts.add(
        "chart",
        new ByteArrayResource(new byte[0]) {
          @Override
          public String getFilename() {
            return "empty-0.1.0.tgz";
          }
        });

    final var answer = this.post(repo, MediaType.MULTIPART_FORM_DATA, parts);

    assertThat(answer.status()).isEqualTo(400);
    assertThat(answer.body()).contains("helmChartEmpty");
  }

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {"application/x-www-form-urlencoded", "application/octet-stream"})
  @DisplayName("a chart posted without multipart answers 400 \"Missing 'chart' part\", not 500")
  void nonMultipartPostIsRefused(final String contentType) {
    final var repo = this.createHelmRepo();

    final var answer = this.post(repo, MediaType.parseMediaType(contentType), "not a chart");

    assertThat(answer.status()).isEqualTo(400);
    assertThat(answer.body()).isEqualTo("Missing 'chart' part");
  }
}
