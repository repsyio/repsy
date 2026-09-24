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
package io.repsy.protocols.npm.shared.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.npm.shared.search.NpmSearchScorer.Scored;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("NpmSearchResult")
class NpmSearchResultTest {

  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00.123456Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static NpmSearchDocument full() {
    return new NpmSearchDocument(
        "acme",
        "left-pad",
        "1.2.0",
        "pads left",
        List.of("pad"),
        Instant.parse("2026-09-24T10:00:00.987654Z"),
        "https://home",
        "https://repo",
        "https://bugs",
        "Ann",
        "ann@example.com",
        "https://ann");
  }

  private static NpmSearchDocument bare() {
    return new NpmSearchDocument(
        null, "bare", "1.0.0", null, List.of(), null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("writes the shape the npm client reads")
  void writesTheNpmShape() throws Exception {
    final var result =
        NpmSearchResult.of(
            List.of(new Scored(full(), 50.0)),
            7,
            100.0,
            Map.of("@acme/left-pad", List.of(new NpmSearchPerson("ann", "ann@example.com"))),
            NOW);

    final var json = MAPPER.readTree(MAPPER.writeValueAsString(result));
    final var object = json.get("objects").get(0);
    final var pkg = object.get("package");

    assertThat(json.get("total").asInt()).isEqualTo(7);
    assertThat(json.get("time").asString()).isEqualTo("2026-09-24T12:00:00.123Z");
    assertThat(pkg.get("name").asString()).isEqualTo("left-pad");
    assertThat(pkg.get("scope").asString()).isEqualTo("acme");
    assertThat(pkg.get("version").asString()).isEqualTo("1.2.0");
    assertThat(pkg.get("description").asString()).isEqualTo("pads left");
    assertThat(pkg.get("keywords").get(0).asString()).isEqualTo("pad");
    assertThat(pkg.get("date").asString()).isEqualTo("2026-09-24T10:00:00.987Z");
    assertThat(pkg.get("links").get("homepage").asString()).isEqualTo("https://home");
    assertThat(pkg.get("links").get("repository").asString()).isEqualTo("https://repo");
    assertThat(pkg.get("links").get("bugs").asString()).isEqualTo("https://bugs");
    assertThat(pkg.get("author").get("name").asString()).isEqualTo("Ann");
    assertThat(pkg.get("publisher").get("username").asString()).isEqualTo("ann");
    assertThat(pkg.get("maintainers").get(0).get("email").asString()).isEqualTo("ann@example.com");
    assertThat(object.get("score").get("final").asDouble()).isEqualTo(0.5);
    assertThat(object.get("score").get("detail").get("quality").asDouble()).isEqualTo(1.0);
    assertThat(object.get("score").get("detail").get("popularity").asDouble()).isEqualTo(1.0);
    assertThat(object.get("score").get("detail").get("maintenance").asDouble()).isEqualTo(1.0);
    assertThat(object.get("searchScore").asDouble()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("always has keywords, links and maintainers, and leaves out what is unknown")
  void alwaysHasTheArraysTheClientReads() throws Exception {
    final var result = NpmSearchResult.of(List.of(new Scored(bare(), 1.0)), 1, 1.0, Map.of(), NOW);

    final var pkg =
        MAPPER.readTree(MAPPER.writeValueAsString(result)).get("objects").get(0).get("package");

    assertThat(pkg.get("scope").asString()).isEqualTo("unscoped");
    assertThat(pkg.get("maintainers").isArray()).isTrue();
    assertThat(pkg.get("maintainers")).isEmpty();
    assertThat(pkg.get("keywords").isArray()).isTrue();
    assertThat(pkg.get("links").isObject()).isTrue();
    assertThat(pkg.has("description")).isFalse();
    assertThat(pkg.has("date")).isFalse();
    assertThat(pkg.has("author")).isFalse();
    assertThat(pkg.has("publisher")).isFalse();
  }

  @Test
  @DisplayName("an empty answer has no objects")
  void empty() {
    final var result = NpmSearchResult.empty(NOW);

    assertThat(result.objects()).isEmpty();
    assertThat(result.total()).isZero();
    assertThat(result.time()).isEqualTo("2026-09-24T12:00:00.123Z");
  }

  @Test
  @DisplayName("formats a whole second without a fraction")
  void formatsWholeSeconds() {
    assertThat(NpmSearchResult.format(Instant.parse("2026-09-24T12:00:00Z")))
        .isEqualTo("2026-09-24T12:00:00Z");
  }
}
