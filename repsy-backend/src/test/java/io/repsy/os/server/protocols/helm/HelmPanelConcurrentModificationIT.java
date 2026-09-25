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
package io.repsy.os.server.protocols.helm;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1342: the panel answers a write that loses an optimistic-lock race with 409 {@code
 * concurrentModification}, end to end. RPS-1325 pinned that mapping with a stub controller only;
 * this drives a real panel endpoint over entities with a {@code @Version}.
 *
 * <p>{@code DELETE /api/helm/charts/{repo}/{chart}/{version}} reads the {@code helm_chart_version}
 * row and deletes it, and the delete carries the {@code @Version} it read. The race is forced, not
 * hoped for: right after the row is read, another connection bumps its {@code version_lock}
 * (another request updated the same version), so the delete matches no row and Spring raises an
 * {@code ObjectOptimisticLockingFailureException}. The row is bumped by plain SQL because a Spring
 * Data repository cannot be a {@code @MockitoSpyBean}; the repository is wrapped in a proxy that
 * calls through instead, as {@code DockerConcurrentTagMoveIT} does for the Docker tag.
 *
 * <p>Runs without a test transaction since the seeded chart and the bump have to be committed. It
 * deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A lost optimistic-lock race on a panel endpoint answers 409 (RPS-1342)")
@Import(HelmPanelConcurrentModificationIT.VersionReadHook.class)
class HelmPanelConcurrentModificationIT extends AbstractIntegrationTest {

  private static final String CHART = "contended";
  private static final String VERSION = "1.0.0";

  private static final AtomicReference<Runnable> AFTER_VERSION_READ = new AtomicReference<>();

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmStorageService helmStorageService;

  /** Runs {@link #AFTER_VERSION_READ} when {@code findByChartAndVersion} returns. */
  @TestConfiguration(proxyBeanMethods = false)
  static class VersionReadHook {

    @Bean
    static BeanPostProcessor versionReadHookProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
          if (!(bean instanceof HelmChartVersionRepository)) {
            return bean;
          }

          return Proxy.newProxyInstance(
              HelmChartVersionRepository.class.getClassLoader(),
              new Class<?>[] {HelmChartVersionRepository.class},
              (proxy, method, args) -> {
                final Object result;
                try {
                  result = method.invoke(bean, args);
                } catch (final InvocationTargetException e) {
                  throw e.getCause();
                }

                final var hook = AFTER_VERSION_READ.get();
                if (hook != null && "findByChartAndVersion".equals(method.getName())) {
                  hook.run();
                }

                return result;
              });
        }
      };
    }
  }

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    AFTER_VERSION_READ.set(null);
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo helmRepo() {
    final var name = uniqueRepoName("helm-panel-race");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  /** A committed ADMIN, who may manage every repo. */
  private User admin() {
    final var info =
        this.userTxService.create(
            uniqueUsername("helm-panel"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(info.getId());

    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private void pushChart(final Repo repo, final String token) throws Exception {
    final var request =
        multipart(UPLOAD_PATH, repo.getName())
            .part(new MockPart("chart", "chart.tgz", chart(CHART, VERSION, "1", null)))
            .header(AUTHORIZATION, token);

    assertThat(
            this.mockMvc
                .perform(request.with(protocolPort()))
                .andReturn()
                .getResponse()
                .getStatus())
        .as("the seeded chart")
        .isEqualTo(201);
  }

  private int versionRows(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from helm_chart_version v
              join helm_chart c on c.id = v.chart_id
            where c.repo_id = ? and c.name = ?
            """,
            Integer.class,
            repo.getId(),
            CHART);

    return count == null ? 0 : count;
  }

  private static Path chartFile(final Repo repo) {
    return storageDirOf(repo).resolve("charts").resolve(CHART + "-" + VERSION + ".tgz");
  }

  @Test
  @DisplayName("deleting a chart version that another request updated meanwhile answers 409")
  void deleteOfAConcurrentlyUpdatedVersionAnswersConflict() throws Exception {
    final var repo = this.helmRepo();
    final var admin = this.admin();
    final var token = this.bearerTokenFor(admin);
    this.pushChart(repo, this.protocolBearerTokenFor(admin));
    final var bumps = new AtomicInteger();
    AFTER_VERSION_READ.set(
        () -> {
          bumps.incrementAndGet();
          this.jdbcTemplate.update(
              """
              update helm_chart_version set version_lock = version_lock + 1
              where chart_id in (select id from helm_chart where repo_id = ?)
              """,
              repo.getId());
        });

    final var result =
        this.mockMvc.perform(
            delete("/api/helm/charts/{repo}/{chart}/{version}", repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, token)
                .with(apiPort()));

    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msgId").value("concurrentModification"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(
            jsonPath("$.text")
                .value(
                    "The item was changed by another request at the same time. Please try again."))
        .andExpect(jsonPath("$.errorCode").isString());
    result.andExpect(header().doesNotExist("Retry-After"));
    assertThat(bumps).as("the race was forced").hasValue(1);
    assertThat(this.versionRows(repo)).as("the losing delete removed nothing").isEqualTo(1);
    assertThat(chartFile(repo)).as("and left the chart file alone").exists();

    AFTER_VERSION_READ.set(null);

    this.mockMvc
        .perform(
            delete("/api/helm/charts/{repo}/{chart}/{version}", repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, token)
                .with(apiPort()))
        .andExpect(status().isOk());
    assertThat(this.versionRows(repo)).as("the same request repeated without contention").isZero();
    assertThat(chartFile(repo)).doesNotExist();
  }
}
