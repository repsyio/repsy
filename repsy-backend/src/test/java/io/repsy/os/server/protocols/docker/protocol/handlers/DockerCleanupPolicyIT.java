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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.docker.shared.cleanup.services.CleanupPolicyFacade;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.shared.http.BareBodyAssertions;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("The Docker cleanup policy (RPS-1882)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DockerCleanupPolicyIT extends AbstractIT {

  private static final String IMAGE = "app";
  private static final String BASE = "/api/repos/%s/docker/cleanup-policy";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @Autowired private CleanupPolicyFacade cleanupPolicyFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String panelToken;

  @BeforeEach
  void setUp() {
    final var info =
        this.userTxService.create(uniqueUsername("cleanup"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.panelToken = this.bearerTokenFor(user.getId(), user.getUsername());
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.protocolBearerTokenFor(user));
  }

  @AfterEach
  void deleteCommittedData() {
    // The policy, images, tags and manifests cascade with the repo.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-cleanup");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String json(final Repo repo, final String suffix, final String method, final String body)
      throws Exception {
    final var url = BASE.formatted(repo.getName()) + suffix;
    final MockHttpServletRequestBuilder request =
        switch (method) {
          case "PUT" -> put(url);
          case "PATCH" -> patch(url);
          default -> get(url);
        };

    return BareBodyAssertions.expectBare(
        this.perform(
            request
                .header(AUTHORIZATION, this.panelToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body)));
  }

  private String read(final Repo repo) throws Exception {
    return BareBodyAssertions.expectBare(
        this.perform(get(BASE.formatted(repo.getName())).header(AUTHORIZATION, this.panelToken)));
  }

  private void enable(final Repo repo, final boolean enabled) throws Exception {
    this.json(repo, "/status", "PATCH", "{\"enabled\":" + enabled + "}");
  }

  private int statusOf(final MockHttpServletRequestBuilder request) throws Exception {
    return this.perform(request).andReturn().getResponse().getStatus();
  }

  private void push(final Repo repo, final String reference, final String layer) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var response = this.wire.putImage(repo, IMAGE, reference, imageManifest(layer));
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
  }

  private void age(final Repo repo, final String tag, final int days) {
    this.jdbcTemplate.update(
        "update docker_tag set created_at = ? where name = ? and image_id in"
            + " (select id from docker_image where repo_id = ?)",
        Timestamp.from(Instant.now().minus(Duration.ofDays(days))),
        tag,
        repo.getId());
  }

  private void ageManifests(final Repo repo, final int days) {
    this.jdbcTemplate.update(
        "update docker_manifest set created_at = ? where image_id in"
            + " (select id from docker_image where repo_id = ?)",
        Timestamp.from(Instant.now().minus(Duration.ofDays(days))),
        repo.getId());
  }

  private List<String> tags(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        "select t.name from docker_tag t join docker_image i on i.id = t.image_id"
            + " where i.repo_id = ? order by t.name",
        String.class,
        repo.getId());
  }

  private int manifestRows(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from docker_manifest m join docker_image i on i.id = m.image_id"
            + " where i.repo_id = ?",
        Integer.class,
        repo.getId());
  }

  private static final String FORM =
      "{\"cadence\":\"WEEKLY\",\"keepLastN\":2,\"keepDays\":7,\"nameRegex\":\"v.*|latest|keep-.*\","
          + "\"nameRegexKeep\":\"keep-.*\"}";

  @Test
  @DisplayName("a repo starts with a disabled default policy that never runs")
  void startsDisabled() throws Exception {
    final var repo = this.dockerRepo();

    final var body = this.read(repo);

    assertThat(JsonPath.<Boolean>read(body, "$.enabled")).isFalse();
    assertThat(JsonPath.<String>read(body, "$.cadence")).isEqualTo("WEEKLY");
    assertThat(JsonPath.<Integer>read(body, "$.keepLastN")).isEqualTo(10);
    assertThat(JsonPath.<Integer>read(body, "$.keepDays")).isEqualTo(7);
    assertThat(JsonPath.<String>read(body, "$.nameRegex")).isEqualTo(".*");
    assertThat(body).as("no nextRunAt while disabled").doesNotContain("nextRunAt");
  }

  @Test
  @DisplayName("deletes nothing while the policy is disabled, however old the tags are")
  void disabledPolicyDeletesNothing() throws Exception {
    final var repo = this.dockerRepo();
    this.read(repo);
    this.push(repo, "v1", "layer-a");
    this.age(repo, "v1", 100);

    this.cleanupPolicyFacade.executeCleanup();

    assertThat(this.tags(repo)).containsExactly("v1");
  }

  @Test
  @DisplayName("the rules need an enabled policy, a valid pattern and values in range")
  void validatesTheRules() throws Exception {
    final var repo = this.dockerRepo();
    this.read(repo);
    final var url = BASE.formatted(repo.getName());

    assertThat(
            this.statusOf(
                put(url)
                    .header(AUTHORIZATION, this.panelToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(FORM)))
        .as("disabled policy")
        .isEqualTo(400);

    this.enable(repo, true);

    assertThat(
            this.statusOf(
                put(url)
                    .header(AUTHORIZATION, this.panelToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(FORM.replace("v.*|", "(unclosed|"))))
        .as("bad regex")
        .isEqualTo(400);
    assertThat(
            this.statusOf(
                put(url)
                    .header(AUTHORIZATION, this.panelToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(FORM.replace("\"keepDays\":7", "\"keepDays\":3"))))
        .as("keepDays below 7")
        .isEqualTo(400);

    final var body = this.json(repo, "", "PUT", FORM);
    assertThat(JsonPath.<Integer>read(body, "$.keepLastN")).isEqualTo(2);
    assertThat(JsonPath.<Boolean>read(body, "$.enabled")).isTrue();
    assertThat(JsonPath.<Object>read(body, "$.nextRunAt")).isNotNull();
  }

  @Test
  @DisplayName("a run needs an enabled policy and is accepted with the policy as Location")
  void runIsAccepted() throws Exception {
    final var repo = this.dockerRepo();
    final var url = BASE.formatted(repo.getName()) + "/actions/run";

    assertThat(this.statusOf(post(url).header(AUTHORIZATION, this.panelToken))).isEqualTo(400);

    this.enable(repo, true);
    final var result = this.perform(post(url).header(AUTHORIZATION, this.panelToken)).andReturn();
    BareBodyAssertions.assertAccepted(result);
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo(BASE.formatted(repo.getName()));
  }

  @Test
  @DisplayName("is refused for a repo that is not Docker, and without credentials")
  void refusesOtherRepos() throws Exception {
    final var name = uniqueRepoName("npm-cleanup");
    this.createdRepoIds.add(this.repoTxService.createRepo(name, RepoType.NPM, false, null).getId());

    assertThat(this.statusOf(get(BASE.formatted(name)).header(AUTHORIZATION, this.panelToken)))
        .isEqualTo(400);
    assertThat(this.statusOf(get(BASE.formatted(this.dockerRepo().getName())))).isEqualTo(401);
  }

  @Test
  @DisplayName("deletes the old tags the rules select, then the untagged manifests they left")
  void deletesWhatTheRulesSelect() throws Exception {
    final var repo = this.dockerRepo();
    this.read(repo);
    this.push(repo, "latest", "layer-latest");
    this.push(repo, "keep-me", "layer-keep");
    this.push(repo, "v1", "layer-v1");
    this.push(repo, "v2", "layer-v2");
    this.push(repo, "v3", "layer-v3");
    this.push(repo, "fresh", "layer-fresh");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-old-bare");
    this.wire.putImage(
        repo,
        IMAGE,
        sha256(bytes(imageManifest("layer-old-bare"))),
        imageManifest("layer-old-bare"));
    this.age(repo, "latest", 60);
    this.age(repo, "keep-me", 50);
    this.age(repo, "v1", 40);
    this.age(repo, "v2", 30);
    this.age(repo, "v3", 20);
    this.age(repo, "fresh", 1);
    this.ageManifests(repo, 40);
    // Pushed after the manifests were aged: younger than the retention, so it is never taken.
    this.wire.pushBlobsOf(repo, IMAGE, "layer-young-bare");
    this.wire.putImage(
        repo,
        IMAGE,
        sha256(bytes(imageManifest("layer-young-bare"))),
        imageManifest("layer-young-bare"));
    assertThat(this.manifestRows(repo)).isEqualTo(8);

    this.json(repo, "/status", "PATCH", "{\"enabled\":true}");
    this.json(repo, "", "PUT", FORM);
    this.cleanupPolicyFacade.executeCleanup();

    // v1 and v2 are old, match and are not among the two newest (fresh, v3); latest and keep-me are
    // exempt by name, fresh by age.
    assertThat(this.tags(repo)).containsExactly("fresh", "keep-me", "latest", "v3");
    // v1, v2 and the old digest-only manifest go; the four tagged ones and the young one stay.
    assertThat(this.manifestRows(repo)).isEqualTo(5);
    final var body = this.read(repo);
    assertThat(JsonPath.<Object>read(body, "$.lastRunAt")).isNotNull();
    assertThat(Instant.parse(JsonPath.read(body, "$.nextRunAt")))
        .isAfter(Instant.now().plus(Duration.ofDays(6)));

    // Not due again until the cadence has passed.
    this.age(repo, "v3", 100);
    this.cleanupPolicyFacade.executeCleanup();
    assertThat(this.tags(repo)).contains("v3");
  }
}
