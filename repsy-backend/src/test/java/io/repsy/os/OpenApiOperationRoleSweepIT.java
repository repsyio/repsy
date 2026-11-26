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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * RPS-2739: the role sweep of the panel API (the IT twin of the e2e {@code api/role-sweep.spec.ts},
 * RPS-1483). Every operation of {@code openapi-spec.yaml} is derived from the spec, so a new route
 * is in the sweep the moment it is in the spec, and is called through the real filter chain:
 *
 * <ol>
 *   <li>an operation that documents {@code 403} (a MANAGE route or an admin-only one) answers a
 *       plain USER 403 {@code accessDenied}, called with names that cannot exist, so the refusal
 *       comes before any 404;
 *   <li>an operation that needs authentication answers an anonymous caller 401;
 *   <li>the reverse: an operation that does not document {@code 403} never answers a USER 403, so a
 *       MANAGE route that forgot to declare it, or a read route that turned admin-only unannounced,
 *       fails here. Only a 403 whose {@code x-forbidden-by} names {@code role} is one a USER gets;
 *       one that names only {@code token-scope} is a refusal of the calling token, which a login
 *       never gets, so those operations are in the reverse half (RPS-2440, RPS-2444).
 * </ol>
 *
 * <p>The sweep is the dynamic half of {@code
 * OpenApiSpecConsistencyIT.everyDocumented403IsManageOrAdmin} (which maps the documented 403s to
 * the handlers), and a floor on the size of the set stops a parser bug from emptying it without a
 * failure. What stays in e2e: the two real-data tests (the panel read back before and after the
 * refused calls, an anonymous caller on a seeded private repository), because they need the
 * deployed edge.
 */
@DisplayName("OpenAPI operation role sweep (RPS-2739)")
class OpenApiOperationRoleSweepIT extends AbstractIT {

  private static final String SPEC_RESOURCE = "openapi/openapi-spec.yaml";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

  private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

  /** A floor, so a parser that silently finds nothing cannot pass (e2e: 120 and 48). */
  private static final int OPERATIONS_AT_LEAST = 120;

  private static final int FORBIDDEN_AT_LEAST = 48;

  /** Uuids of users, deploy tokens, key stores, public keys and scans that nothing has. */
  private static final String NO_SUCH_UUID = "00000000-0000-4000-8000-000000000000";

  private static final String NO_SUCH_REPO = "sweep-no-such-repo";

  /**
   * Operations the reverse sweep cannot call as the calling USER without harming the caller, each
   * with the reason. The anonymous sweep still calls them (a 401 changes nothing).
   */
  private static final Set<String> REVERSE_SKIP =
      Set.of(
          // deletes the calling USER
          "deleteProfile",
          // renames the calling USER
          "updateUsername",
          // changes the calling USER's password
          "updatePassword");

  /** Own deletes: any signed-in caller may revoke or delete what is theirs, no MANAGE. */
  private static final Set<String> OWN_DELETES =
      Set.of(
          "deleteProfile",
          "revokeAccessToken",
          "revokeCurrentConnectorToken",
          "revokeConnectorToken");

  private record Operation(
      HttpMethod method,
      String path,
      String id,
      boolean declares403,
      boolean isPublic,
      List<String> requiredQuery,
      boolean hasBody) {

    @Override
    public String toString() {
      return this.method + " " + this.path + " (" + this.id + ")";
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Operation> loadOperations() {
    final var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);

    final Map<String, Object> doc;
    try (var in = new ClassPathResource(SPEC_RESOURCE).getInputStream()) {
      doc = (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }

    final var operations = new ArrayList<Operation>();

    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, item) ->
                item.forEach(
                    (method, value) -> {
                      if (!HTTP_METHODS.contains(method)) {
                        return;
                      }
                      final var raw = (Map<String, Object>) value;
                      final var responses = (Map<String, Object>) raw.get("responses");
                      final var security = (List<Object>) raw.get("security");
                      final var parameters =
                          (List<Map<String, Object>>) raw.getOrDefault("parameters", List.of());

                      operations.add(
                          new Operation(
                              HttpMethod.valueOf(method.toUpperCase()),
                              path,
                              (String) raw.get("operationId"),
                              responses.containsKey("403")
                                  && raw.get("x-forbidden-by") instanceof final List<?> reasons
                                  && reasons.contains("role"),
                              security != null && security.isEmpty(),
                              parameters.stream()
                                  .filter(
                                      p ->
                                          "query".equals(p.get("in"))
                                              && Boolean.TRUE.equals(p.get("required")))
                                  .map(p -> (String) p.get("name"))
                                  .toList(),
                              raw.containsKey("requestBody")));
                    }));

    return operations;
  }

  private static final List<Operation> OPERATIONS = loadOperations();

  static Stream<Arguments> forbiddenOperations() {
    return OPERATIONS.stream().filter(Operation::declares403).map(Arguments::of);
  }

  static Stream<Arguments> authenticatedOperations() {
    return OPERATIONS.stream().filter(op -> !op.isPublic()).map(Arguments::of);
  }

  static Stream<Arguments> publicOperations() {
    return OPERATIONS.stream().filter(Operation::isPublic).map(Arguments::of);
  }

  static Stream<Arguments> notForbiddenOperations() {
    return OPERATIONS.stream()
        .filter(op -> !op.declares403() && !REVERSE_SKIP.contains(op.id()))
        .map(Arguments::of);
  }

  private static String placeholderFor(final String name) {
    return switch (name) {
      case "repoName" -> NO_SUCH_REPO;
      case "userId", "tokenId", "keyStoreId", "publicKeyId", "scanId" -> NO_SUCH_UUID;
      case "digest" -> "sha256:" + "0".repeat(64);
      case "version" -> "1.0.0";
      default -> "sweep-none";
    };
  }

  /** Bodies that pass validation, on names that cannot exist, for the operations that need one. */
  private static String bodyFor(final Operation op) {
    if (!op.hasBody()) {
      return null;
    }
    return switch (op.id()) {
      case "updateUser" -> "{\"username\":\"sweep-nobody\",\"role\":\"USER\"}";
      case "createUser" ->
          "{\"username\":\"sweep-nobody\",\"password\":\"Sweep-Pwd1!\",\"role\":\"USER\"}";
      case "createRepository" ->
          "{\"name\":\"sweep-newrepo\",\"type\":\"MAVEN\",\"privateRepo\":true}";
      case "updateRepoSettings" -> "{\"privateRepo\":false,\"allowOverride\":false}";
      case "updateRepo" -> "{\"description\":\"changed by the sweep\"}";
      case "createDeployToken" -> "{\"name\":\"sweep-token\"}";
      case "createMavenKeyStore" -> "{\"allowedKeyserverId\":\"none\"}";
      default -> "{}";
    };
  }

  private static MockHttpServletRequestBuilder requestFor(final Operation op) {
    final var path = PLACEHOLDER.matcher(op.path()).replaceAll(m -> placeholderFor(m.group(1)));
    final var query =
        op.requiredQuery().stream()
            .map(
                name ->
                    URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(placeholderFor(name), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    final var builder = request(op.method(), query.isEmpty() ? path : path + "?" + query);
    final var body = bodyFor(op);

    return body == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  @DisplayName("the set is derived from the spec and is not empty")
  void setIsDerivedFromSpec() {
    assertThat(OPERATIONS).hasSizeGreaterThanOrEqualTo(OPERATIONS_AT_LEAST);
    assertThat(OPERATIONS.stream().filter(Operation::declares403).count())
        .isGreaterThanOrEqualTo(FORBIDDEN_AT_LEAST);
  }

  @Test
  @DisplayName("the 403 set holds the routes that are known to be MANAGE or admin only")
  void forbiddenSetHoldsKnownRoutes() {
    final var ids =
        OPERATIONS.stream()
            .filter(Operation::declares403)
            .map(Operation::id)
            .collect(Collectors.toSet());

    // Everything under /api/users, and creating a repository, is admin only.
    assertThat(
            OPERATIONS.stream().filter(op -> op.path().startsWith("/api/users")).map(Operation::id))
        .isNotEmpty()
        .allMatch(ids::contains);
    assertThat(ids)
        .contains(
            "createRepository",
            "getRepoSettings",
            "updateRepoSettings",
            "updateRepo",
            "listDeployTokens",
            "createDeployToken",
            "revokeDeployToken",
            "rotateDeployToken",
            "listMavenKeyStores",
            "createMavenKeyStore",
            "deleteMavenKeyStore",
            "listMavenPgpPublicKeys",
            "createMavenPgpPublicKey",
            "deleteMavenPgpPublicKey",
            "deleteRepo");

    // Every delete of a package, version or repo, in every protocol; only the deletes of what a
    // caller owns are not MANAGE.
    final var deletes =
        OPERATIONS.stream()
            .filter(op -> op.method() == HttpMethod.DELETE && !OWN_DELETES.contains(op.id()))
            .toList();

    assertThat(deletes).hasSizeGreaterThanOrEqualTo(28);
    assertThat(deletes.stream().map(Operation::id)).allMatch(ids::contains);
  }

  @Test
  @DisplayName("every skipped operation exists in the spec")
  void skippedOperationsExist() {
    assertThat(OPERATIONS.stream().map(Operation::id)).containsAll(REVERSE_SKIP);
  }

  @ParameterizedTest(name = "a USER gets 403 before anything else on {0}")
  @MethodSource("forbiddenOperations")
  void userIsForbidden(final Operation op) throws Exception {
    expectForbidden(this.perform(requestFor(op).header(AUTHORIZATION, this.userBearerToken())));
  }

  @ParameterizedTest(name = "an anonymous caller gets 401 on {0}")
  @MethodSource("authenticatedOperations")
  void anonymousIsUnauthorized(final Operation op) throws Exception {
    final var status = this.perform(requestFor(op)).andReturn().getResponse().getStatus();

    assertThat(status).as(op.toString()).isEqualTo(401);
  }

  @ParameterizedTest(name = "an anonymous caller is let through on the public {0}")
  @MethodSource("publicOperations")
  void anonymousPassesPublic(final Operation op) throws Exception {
    final var status = this.perform(requestFor(op)).andReturn().getResponse().getStatus();

    assertThat(status).as(op.toString()).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "a USER is not refused with 403 on {0}")
  @MethodSource("notForbiddenOperations")
  void userIsNotForbiddenElsewhere(final Operation op) throws Exception {
    final var status =
        this.perform(requestFor(op).header(AUTHORIZATION, this.userBearerToken()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as(op.toString()).isNotEqualTo(403);
  }
}
