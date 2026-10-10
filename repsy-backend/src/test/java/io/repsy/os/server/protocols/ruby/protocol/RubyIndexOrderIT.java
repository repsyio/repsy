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
package io.repsy.os.server.protocols.ruby.protocol;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIT;
import io.repsy.os.HeapOrder;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * RPS-1614: the compact index lists the versions of a gem line by line, in the order of the rows,
 * and stores an MD5 of that body that a client compares. The order was {@code created_at} alone, so
 * versions stored in the same instant came back in the order the heap held them, which changes with
 * every update of a row. The id ends the order.
 */
@DisplayName("Ruby /info lists versions in a fixed order (RPS-1614)")
class RubyIndexOrderIT extends AbstractIT {

  private void push(final Repo repo, final String token, final String name, final String version)
      throws Exception {
    final var status =
        this.mockMvc
            .perform(
                post(PUBLISH_PATH, repo.getName())
                    .header(AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(gem(name, version))
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(200);
  }

  private List<String> versionLines(final Repo repo, final String token, final String name)
      throws Exception {
    final var body =
        this.mockMvc
            .perform(
                get("/{repo}/info/{name}", repo.getName(), name)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    return body.lines()
        .dropWhile(line -> !line.equals("---"))
        .skip(1)
        .map(line -> line.substring(0, line.indexOf(' ')))
        .toList();
  }

  private UUID versionId(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        "select v.\"id\" from \"ruby_gem_version\" v join \"ruby_gem\" g on g.\"id\" = v.\"gem_id\""
            + " where g.\"repo_id\" = ? and g.\"name\" = ? and v.\"version\" = ?",
        UUID.class,
        repo.getId(),
        name,
        version);
  }

  @Test
  @DisplayName("versions stored in the same instant come in the order of their ids")
  void versionsOfOneInstantAreOrderedById() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("rps1614"));
    final var token = this.adminProtocolBearerToken();

    this.push(repo, token, "order-gem", "1.0.0");
    this.push(repo, token, "order-gem", "2.0.0");
    this.push(repo, token, "order-gem", "3.0.0");

    final var first = this.versionId(repo, "order-gem", "1.0.0");
    final var second = this.versionId(repo, "order-gem", "2.0.0");
    final var third = this.versionId(repo, "order-gem", "3.0.0");

    // The same instant for all three, and a heap that holds them the other way round.
    this.jdbcTemplate.update(
        "update \"ruby_gem_version\" set \"created_at\" = ? where \"id\" in (?, ?, ?)",
        Timestamp.from(Instant.now().minusSeconds(60)),
        first,
        second,
        third);
    HeapOrder.rewriteInOrder(this.jdbcTemplate, "ruby_gem_version", List.of(third, first, second));

    assertThat(this.versionLines(repo, token, "order-gem"))
        .containsExactly("1.0.0", "2.0.0", "3.0.0");
  }
}
