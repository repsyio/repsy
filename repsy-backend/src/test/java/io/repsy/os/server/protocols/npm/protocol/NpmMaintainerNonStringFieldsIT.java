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
 * RPS-2199: {@code NpmPackageService#addMaintainers} cast a maintainer's email and url to {@code
 * String}, so a number, object or list there answered 500 with a ClassCastException. Both columns
 * are nullable, so a non-string value is stored as absent and the maintainer is kept (the name,
 * which is NOT NULL, drops the whole entry instead, RPS-2186).
 */
@DisplayName("npm publish with a non-string maintainer email or url (RPS-2199)")
class NpmMaintainerNonStringFieldsIT extends AbstractIT {

  private static final String NAME = "left-pad";

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName(
      "keeps the maintainer, stores a numeric email and an object url as null, answers 200")
  void nullsNonStringEmailAndUrl() throws Exception {
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
                    Map.of("name", "jane", "email", 42, "url", Map.of("href", "https://x.test")),
                    Map.of("name", "joe", "email", "joe@example.org", "url", List.of("a", "b")))));

    this.mockMvc
        .perform(
            put("/{repo}/{name}", repo.getName(), NAME)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    final var rows =
        this.jdbcTemplate.queryForList(
            """
            select m.name, m.email, m.url
            from npm_package_maintainer m
            join npm_package_version v on v.id = m.package_version_id
            join npm_package p on p.id = v.package_id
            where p.repo_id = ?
            order by m.name
            """,
            repo.getId());

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("name")).isEqualTo("jane");
    assertThat(rows.get(0).get("email")).isNull();
    assertThat(rows.get(0).get("url")).isNull();
    assertThat(rows.get(1).get("name")).isEqualTo("joe");
    assertThat(rows.get(1).get("email")).isEqualTo("joe@example.org");
    assertThat(rows.get(1).get("url")).isNull();
  }
}
