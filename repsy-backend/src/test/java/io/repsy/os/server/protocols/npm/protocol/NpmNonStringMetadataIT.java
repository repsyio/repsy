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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2210 (follow-up of RPS-2199): the publish path cast values of the package.json to {@code
 * String} or {@code Map} without checking them, so a number, object or list where a string belongs
 * answered 400 (a ClassCastException the facade caught) or 500 (an NPE, a {@code
 * NoSuchElementException}, a ClassCastException outside that catch), or stored the value's {@code
 * toString} as the description or homepage.
 *
 * <p>The rule is RPS-2199's: an optional value that is not a string is nulled and the publish kept;
 * a value the publish cannot do without is refused with a 400, never a 500.
 */
@DisplayName("npm publish with non-string package.json values (RPS-2210)")
class NpmNonStringMetadataIT extends AbstractIT {

  private static final String NAME = "left-pad";
  private static final String VERSION = "1.0.0";

  @Autowired private ObjectMapper objectMapper;

  private static Arguments optional(final String label, final Map<String, Object> versionFields) {
    return Arguments.of(label, versionFields);
  }

  private static Arguments required(final String label, final Consumer<Map<String, Object>> edit) {
    return Arguments.of(label, edit);
  }

  private static Map<String, Object> mapOf(final Object... keysAndValues) {
    final var map = new LinkedHashMap<String, Object>();

    for (var i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }

    return map;
  }

  static Stream<Arguments> optionalValues() {
    return Stream.of(
        optional("description number", mapOf("description", 42)),
        optional("description object", mapOf("description", mapOf("a", 1))),
        optional("homepage number", mapOf("homepage", 42)),
        optional("homepage list", mapOf("homepage", List.of("https://x.test"))),
        optional("license number", mapOf("license", 42)),
        optional("license type number", mapOf("license", mapOf("type", 42))),
        optional("license type object", mapOf("license", mapOf("type", mapOf("a", 1)))),
        optional("author number", mapOf("author", 42)),
        optional(
            "author members",
            mapOf("author", mapOf("name", 42, "email", mapOf("a", 1), "url", List.of("u")))),
        optional("bugs number", mapOf("bugs", 42)),
        optional("bugs members", mapOf("bugs", mapOf("url", 42, "email", true))),
        optional("repository number", mapOf("repository", 42)),
        optional("repository members", mapOf("repository", mapOf("type", 1, "url", mapOf("a", 1)))),
        optional("keywords number", mapOf("keywords", 42)),
        optional("keywords mixed", mapOf("keywords", Arrays.asList("ok", 1, null, mapOf("a", 1)))),
        optional("maintainers number", mapOf("maintainers", 42)),
        optional(
            "maintainers mixed",
            mapOf(
                "maintainers",
                Arrays.asList(42, "jane", null, List.of("x"), mapOf("name", "jane")))));
  }

  static Stream<Arguments> requiredValues() {
    return Stream.of(
        required("dist-tags value number", body -> body.put("dist-tags", mapOf("latest", 5))),
        required("dist-tags value null", body -> body.put("dist-tags", mapOf("latest", null))),
        required(
            "second dist-tag value object",
            body -> body.put("dist-tags", mapOf("latest", VERSION, "next", mapOf("a", 1)))),
        required("dist-tags string", body -> body.put("dist-tags", "latest")),
        required("dist-tags empty", body -> body.put("dist-tags", Map.of())),
        required("dist-tags absent", body -> body.remove("dist-tags")),
        required("versions empty", body -> body.put("versions", Map.of())),
        required("versions list", body -> body.put("versions", List.of(mapOf("name", NAME)))),
        required("versions absent", body -> body.remove("versions")),
        required("version entry number", body -> body.put("versions", mapOf(VERSION, 5))),
        required("version entry null", body -> body.put("versions", mapOf(VERSION, null))),
        required("version name absent", body -> version(body).remove("name")),
        required("version name number", body -> version(body).put("name", 42)),
        required("version field number", body -> version(body).put("version", 42)),
        required("top-level name number", body -> body.put("name", 42)),
        required("top-level _id object", body -> body.put("_id", mapOf("a", 1))),
        required("dist absent", body -> version(body).remove("dist")),
        required("dist string", body -> version(body).put("dist", "x")),
        required("dist empty", body -> version(body).put("dist", Map.of())),
        required("dist tarball number", body -> version(body).put("dist", mapOf("tarball", 5))),
        required("attachments absent", body -> body.remove("_attachments")),
        required("attachments empty", body -> body.put("_attachments", Map.of())),
        required("attachments list", body -> body.put("_attachments", List.of())),
        required("attachment number", body -> body.put("_attachments", mapOf("f.tgz", 5))),
        required(
            "attachment data number",
            body -> body.put("_attachments", mapOf("f.tgz", mapOf("data", 5)))),
        required(
            "attachment data absent",
            body -> body.put("_attachments", mapOf("f.tgz", mapOf("length", 3)))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> version(final Map<String, Object> body) {
    return (Map<String, Object>) ((Map<String, Object>) body.get("versions")).get(VERSION);
  }

  private Map<String, Object> bodyOf(final String repoName, final Map<String, Object> extra) {
    return this.objectMapper.readValue(
        NpmPublishBodies.body(this.objectMapper, repoName, NAME, VERSION, extra),
        new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private int publish(final String repoName, final String token, final Map<String, Object> body)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{name}", repoName, NAME)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsBytes(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @ParameterizedTest(name = "optional: {0} is nulled, the publish is kept")
  @MethodSource("optionalValues")
  @DisplayName("an optional value that is not a string is stored as absent and answers 200")
  void nullsOptionalValue(final String label, final Map<String, Object> versionFields)
      throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.adminProtocolBearerToken();

    assertThat(this.publish(repo.getName(), token, this.bodyOf(repo.getName(), versionFields)))
        .as(label)
        .isEqualTo(200);

    final var row =
        this.jdbcTemplate.queryForMap(
            """
            select v.description, v.homepage, v.license, v.author_name, v.author_email,
                v.author_url, v.bugs_url, v.bugs_email, v.repository_type, v.repository_url
            from npm_package_version v
            join npm_package p on p.id = v.package_id
            where p.repo_id = ?
            """,
            repo.getId());

    assertThat(row.values()).as(label).containsOnlyNulls();

    final var keywords =
        this.jdbcTemplate.queryForList(
            """
            select k.keyword
            from npm_package_keyword k
            join npm_package_version v on v.id = k.package_version_id
            join npm_package p on p.id = v.package_id
            where p.repo_id = ?
            """,
            String.class,
            repo.getId());
    final var maintainers =
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

    assertThat(keywords).isSubsetOf("ok");
    assertThat(maintainers).isSubsetOf("jane");

    final var packument =
        this.mockMvc
            .perform(
                get("/{repo}/{name}", repo.getName(), NAME)
                    .with(protocolPort())
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();

    assertThat(packument.getStatus()).as(label).isEqualTo(200);
    assertThat(
            isClean(
                version(
                    this.objectMapper.readValue(
                        packument.getContentAsString(StandardCharsets.UTF_8),
                        new TypeReference<LinkedHashMap<String, Object>>() {}))))
        .as(label + " is served without the non-string value")
        .isTrue();
  }

  @ParameterizedTest(name = "required: {0} is a 400")
  @MethodSource("requiredValues")
  @DisplayName("a value the publish cannot do without, when not a string, answers 400, not 500")
  void refusesRequiredValue(final String label, final Consumer<Map<String, Object>> edit)
      throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.adminProtocolBearerToken();
    final var body = this.bodyOf(repo.getName(), Map.of());

    edit.accept(body);

    assertThat(this.publish(repo.getName(), token, body)).as(label).isEqualTo(400);
  }

  private static boolean isClean(final Map<String, Object> version) {
    return isStringOrNull(version.get("description"))
        && isStringOrNull(version.get("homepage"))
        && isStringOrNullOrMapOf(version.get("license"))
        && isStringOrNullOrMapOf(version.get("author"))
        && isStringOrNullOrMapOf(version.get("bugs"))
        && isStringOrNullOrMapOf(version.get("repository"))
        && allStrings(version.get("keywords"))
        && allMapsWithName(version.get("maintainers"));
  }

  private static boolean isStringOrNull(final Object value) {
    return value == null || value instanceof String;
  }

  private static boolean isStringOrNullOrMapOf(final Object value) {
    if (value instanceof final Map<?, ?> map) {
      return map.values().stream().allMatch(NpmNonStringMetadataIT::isStringOrNull);
    }

    return isStringOrNull(value);
  }

  private static boolean allStrings(final Object value) {
    if (value == null) {
      return true;
    }

    return value instanceof final Collection<?> items
        && items.stream().allMatch(String.class::isInstance);
  }

  private static boolean allMapsWithName(final Object value) {
    if (value == null) {
      return true;
    }

    return value instanceof final Collection<?> items
        && items.stream()
            .allMatch(
                item -> item instanceof final Map<?, ?> map && map.get("name") instanceof String);
  }
}
