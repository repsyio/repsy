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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2186: {@code npm_package_maintainer.name} is {@code NOT NULL} but the publish path copied
 * whatever the packument carried, so a maintainer entry without a name failed at flush with a
 * constraint violation and the publish answered 500. Maintainers are optional metadata, so the
 * nameless entry is dropped (like an over-long one, RPS-1136) and the publish succeeds.
 */
@DisplayName("npm publish with a nameless maintainer (RPS-2186)")
class NpmNamelessMaintainerIT extends AbstractIT {

  private static final String NAME = "left-pad";

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("drops the maintainer without a name, keeps the named one and answers 200")
  void dropsNamelessMaintainer() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.adminProtocolBearerToken();
    final var body =
        NpmPublishBodies.body(
            this.objectMapper,
            repo.getName(),
            NAME,
            "1.0.0",
            Map.of(
                "maintainers",
                List.of(
                    Map.of("name", "jane", "email", "jane@example.org"),
                    Map.of("email", "nameless@example.org"))));

    this.mockMvc
        .perform(
            put("/{repo}/{name}", repo.getName(), NAME)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    final var names =
        this.jdbcTemplate.queryForList(
            """
            select m.name
            from npm_package_maintainer m
            join npm_package_version v on v.id = m.package_version_id
            join npm_package p on p.id = v.package_id
            where p.repo_id = ?
            """,
            String.class,
            repo.getId());

    assertThat(names).containsExactly("jane");
  }
}
