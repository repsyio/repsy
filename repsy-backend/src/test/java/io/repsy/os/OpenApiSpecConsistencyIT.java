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

import com.jayway.jsonpath.JsonPath;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.utils.RepoUtils;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * RPS-1269: keeps {@code openapi-spec.yaml} and the hand-written panel controllers in step.
 *
 * <p>The backend generates only DTOs from the spec ({@code generateApis} is off), so nothing else
 * stops a path, a path variable, the security or the error responses of an operation from drifting
 * from what the controller does. Both generated clients (Angular and the e2e harness) are built
 * from the spec, so a drift is a wrong client, not a compile error.
 *
 * <p>The panel routes are the handlers of a {@code @RestApiPort} controller under {@code /api/}.
 * Everything else is left out on purpose: the wire protocols of the package formats are served by
 * the catch-all {@code ProtocolRouterController} (no {@code @RestApiPort}) and are not part of the
 * spec, and the SPA forwarding routes of {@code SpaController} are on the API port but outside
 * {@code /api/}.
 *
 * <p>Some drift is already there. Each such finding is listed in a {@code KNOWN_*} map below with
 * the ticket that removes it, so the test fails for any <em>new</em> drift and also fails once a
 * listed finding is gone (remove the entry then).
 */
@DisplayName("OpenAPI spec and panel controllers")
class OpenApiSpecConsistencyIT extends AbstractIntegrationTest {

  private static final String SPEC_RESOURCE = "openapi/openapi-spec.yaml";

  /**
   * RPS-1897: the tag table, shared with the Cloud IT (it reads the same file from the classpath).
   * {@code allowedTags} is every tag either spec may use; {@code os}, {@code cloud} and {@code
   * cloudAdmin} map the old springdoc tag of each spec to its new one.
   */
  private static final String TAG_TABLE_RESOURCE = "openapi/openapi-tags.json";

  private static final String CONTROLLER_SUFFIX = "-controller";
  private static final Pattern KEBAB_CASE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
  private static final String PANEL_PREFIX = "/api/";
  private static final String REPOS_PREFIX = "/api/repos/";
  private static final String BEARER = "bearerAuth";

  /** The success components of components/responses; every other component is an error response. */
  private static final Set<String> SUCCESS_RESPONSES = Set.of("Created", "NoContent", "Accepted");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  /** {@code {name}} or {@code {name:regex}}: the group is the variable name. */
  private static final Pattern VARIABLE = Pattern.compile("\\{([^}:/]+)(?::[^}]*)?}");

  /**
   * The operations that need no credentials at all, and why. A route may only be added here on
   * purpose: a public route is one nobody can protect by forgetting the header.
   *
   * <ul>
   *   <li>{@code login}, {@code refreshToken}: they are how a client gets a token.
   *   <li>{@code logout} (RPS-1622): the refresh token being discarded is its own credential, the
   *       same as {@code refreshToken}; it revokes the token's family instead of minting a new
   *       pair.
   *   <li>{@code getSupportedRepoTypes}: a constant list, {@code SecurityScanController} takes no
   *       credentials for it.
   * </ul>
   */
  private static final Set<String> PUBLIC_OPERATIONS =
      Set.of("login", "refreshToken", "logout", "getSupportedRepoTypes");

  /**
   * RPS-1593: the operations that answer 403 to a USER because their handler calls {@code
   * PanelAuthHelper#requireAdmin} in its body, not because of {@code @RepoOperation(MANAGE)}. That
   * call is invisible to reflection, so the set is written out, like {@link #PUBLIC_OPERATIONS}: a
   * new admin-only route is added here on purpose.
   *
   * <ul>
   *   <li>{@code UserController}: {@code listUsers}, {@code countAdmins}, {@code createUser},
   *       {@code updateUser}, {@code deleteUser}, {@code resetPassword}.
   *   <li>{@code RepoCollectionController}: {@code createRepository}.
   *   <li>{@code SecurityScanController}: {@code listSecurityScans}, {@code
   *       getSecurityScansSummary}.
   * </ul>
   *
   * The e2e role sweep ({@code e2e/tests/api/role-sweep.spec.ts}, {@code e2e/README.md} "USER-role
   * 403 sweep") calls every operation that documents 403 as a USER, and the ones that do not, and
   * expects 403 for the first and anything else for the second. This test is its static twin: it
   * needs no stack, runs in every {@code mvn verify} and names the operation.
   */
  private static final Set<String> ADMIN_OPERATIONS =
      Set.of(
          "listUsers",
          "countAdmins",
          "createUser",
          "updateUser",
          "deleteUser",
          "resetPassword",
          "createRepository",
          "listSecurityScans",
          "getSecurityScansSummary");

  /**
   * RPS-1269: the one name a path variable has for each role, across every panel controller. A
   * generated client names its arguments after these, so two names for one role are two spellings
   * of the same thing in every client. A new variable is added here on purpose, with its role, and
   * never as a second name for a role that is already listed.
   *
   * <ul>
   *   <li>{@code repoName}: the repository. {@code ResolverUtils.REPO_NAME} reads it.
   *   <li>{@code version}: the version of any package, artifact, crate, chart, gem or release. The
   *       only other name is {@code reference} for Docker: an OCI reference is a tag or a digest.
   *   <li>{@code packageName}, {@code chartName}, {@code crateName}, {@code imageName}, {@code
   *       artifactName}, {@code groupName}, {@code tagName}, {@code scope}: the item of a protocol,
   *       named after what that protocol calls it.
   *   <li>{@code digest}, {@code userId}, {@code tokenId}, {@code publicKeyId}, {@code keyStoreId},
   *       {@code scanId}: the identifier of a panel entity.
   * </ul>
   */
  private static final Set<String> PATH_VARIABLES =
      Set.of(
          "repoName",
          "version",
          "reference",
          "packageName",
          "chartName",
          "crateName",
          "imageName",
          "artifactName",
          "groupName",
          "tagName",
          "scope",
          "digest",
          "userId",
          "tokenId",
          "publicKeyId",
          "keyStoreId",
          "scanId");

  /** What a panel JSON property or a query or path parameter is called: camelCase. */
  private static final Pattern CAMEL_CASE = Pattern.compile("[a-z][a-zA-Z0-9]*");

  /**
   * The panel schemas that may keep a non-camelCase property name because a wire protocol shares
   * the DTO, as {@code Schema.property}. It is empty on purpose (RPS-1269): the panel has its own
   * schema for everything the wire protocols also serve ({@code CrateInfo}, {@code
   * CrateVersionInfo}, {@code CrateDependencyInfo} next to the crates.io shapes). An entry needs a
   * comment naming the wire protocol and the reason there is no separate panel schema.
   */
  private static final Set<String> WIRE_SHARED_PROPERTIES = Set.of();

  @Autowired private RequestMappingHandlerMapping handlerMapping;

  // ---------------------------------------------------------------------------------------------
  // Paths and variables
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("every (method, path) pair is in both the spec and the controllers")
  void everyOperationIsInBothSets() throws IOException {
    final var spec = specOperations(loadSpec());
    final var routes = this.panelRoutes();

    final var findings = new TreeSet<String>();

    for (final var key : spec.keySet()) {
      if (routes.stream().noneMatch(route -> route.key().equals(key))) {
        findings.add("in the spec, no controller: " + key);
      }
    }

    for (final var route : routes) {
      if (!spec.containsKey(route.key())) {
        findings.add("in a controller, not in the spec: " + route.key());
      }
    }

    assertNoNewFindings("spec/controller pairs", findings, Map.of());
  }

  @Test
  @DisplayName("path variable names match between the spec and the controller, per operation")
  void pathVariableNamesMatch() throws IOException {
    final var spec = specOperations(loadSpec());
    final var routes = this.panelRoutes();
    final var findings = new TreeSet<String>();

    for (final var route : routes) {
      final var operation = spec.get(route.key());

      if (operation == null) {
        continue;
      }

      if (!operation.variables().equals(route.variables())) {
        findings.add(
            route.key()
                + ": spec "
                + operation.variables()
                + " but controller "
                + route.variables());
      }

      // One handler method may serve several templates (an optional {scope}), so a bound name
      // only has to be a variable of one of them.
      final var served = new TreeSet<String>();
      routes.stream()
          .filter(other -> other.handler().getMethod().equals(route.handler().getMethod()))
          .forEach(other -> served.addAll(other.variables()));

      for (final var bound : route.boundPathVariables()) {
        if (!served.contains(bound)) {
          findings.add(
              route.key() + ": @PathVariable " + bound + " is in no mapping of its method");
        }
      }
    }

    assertNoNewFindings("path variable names", findings, Map.of());
  }

  @Test
  @DisplayName(
      "every path variable of an operation is declared as a path parameter, and only those")
  void pathParametersAreDeclared() throws IOException {
    final var doc = loadSpec();
    final var findings = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      final var declared = new TreeSet<String>();

      for (final var parameter : operation.parameters(doc)) {
        if ("path".equals(parameter.get("in"))) {
          declared.add(String.valueOf(parameter.get("name")));
        }
      }

      if (!declared.equals(new TreeSet<>(operation.variables()))) {
        findings.add(
            operation.key()
                + ": path "
                + operation.variables()
                + " but declared path parameters "
                + declared);
      }
    }

    assertNoNewFindings("declared path parameters", findings, Map.of());
  }

  @Test
  @DisplayName("no two paths put different variables at the same position under one prefix")
  void siblingVariablesShareOneName() throws IOException {
    final var templates = new TreeSet<String>();
    specOperations(loadSpec()).values().forEach(operation -> templates.add(operation.template()));
    this.panelRoutes().forEach(route -> templates.add(route.pattern()));

    final var names = new TreeMap<String, Set<String>>();

    for (final var first : templates) {
      for (final var second : templates) {
        collectClash(first, second, names);
      }
    }

    final var findings = new TreeSet<String>();
    names.forEach(
        (prefix, variables) -> findings.add(prefix + " -> " + String.join(" | ", variables)));

    assertNoNewFindings("sibling variable names", findings, Map.of());
  }

  @Test
  @DisplayName("every path variable is the one name its role has, and a version is {version}")
  void pathVariablesUseTheOneNameOfTheirRole() throws IOException {
    final var templates = new TreeSet<String>();
    specOperations(loadSpec()).values().forEach(operation -> templates.add(operation.template()));
    this.panelRoutes().forEach(route -> templates.add(route.pattern()));

    final var findings = new TreeSet<String>();

    for (final var template : templates) {
      for (final var variable : variablesOf(template)) {
        if (!PATH_VARIABLES.contains(variable)) {
          findings.add(template + ": {" + variable + "} is not a known path variable");
        }

        // The set above already rejects vers, versionName, releaseVersion and artifactVersion; this
        // says why, and also catches a new spelling nobody thought of.
        if (variable.toLowerCase(Locale.ROOT).contains("vers") && !"version".equals(variable)) {
          findings.add(template + ": {" + variable + "} is a version, call it {version}");
        }
      }
    }

    assertNoNewFindings("path variable names per role", findings, Map.of());
  }

  @Test
  @DisplayName("every literal next to {repoName} under /api/repos/ is a reserved repo name")
  void literalSiblingsOfRepoNameAreReserved() throws IOException {
    final var templates = new TreeSet<String>();
    specOperations(loadSpec()).values().forEach(operation -> templates.add(operation.template()));
    this.panelRoutes().forEach(route -> templates.add(route.pattern()));

    final var findings = new TreeSet<String>();

    for (final var template : templates) {
      if (!template.startsWith(REPOS_PREFIX)) {
        continue;
      }

      final var segment = template.substring(REPOS_PREFIX.length()).split("/", 2)[0];

      if (!VARIABLE.matcher(segment).matches() && !isReservedRepoName(segment)) {
        findings.add(segment);
      }
    }

    assertThat(templates)
        .as("the rule is only meaningful while {repoName} is what sits under /api/repos/")
        .anyMatch(template -> template.startsWith(REPOS_PREFIX + "{repoName}"));

    assertNoNewFindings("unreserved literals under /api/repos/", findings, Map.of());
  }

  @Test
  @DisplayName("repo operations keep the repoName variable the auth interceptor reads")
  void repoOperationsKeepTheRepoNameVariable() {
    final var findings = new TreeSet<String>();

    for (final var route : this.panelRoutes()) {
      if (route.repoOperation() != null && !route.variables().contains("repoName")) {
        findings.add(route.key());
      }
    }

    assertNoNewFindings("@RepoOperation routes without {repoName}", findings, Map.of());
  }

  // ---------------------------------------------------------------------------------------------
  // Security and errors
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the spec declares the bearer scheme and applies it to every operation by default")
  void bearerSchemeIsGlobal() throws IOException {
    final var doc = loadSpec();
    final var schemes = asMap(asMap(doc.get("components")).get("securitySchemes"));

    assertThat(schemes).containsKey(BEARER);
    assertThat(asMap(schemes.get(BEARER)))
        .containsEntry("type", "http")
        .containsEntry("scheme", "bearer");
    assertThat(asList(doc.get("security")))
        .as("the top-level security requirement")
        .containsExactly(Map.of(BEARER, List.of()));

    for (final var operation : specOperations(doc).values()) {
      final var explicit = operation.raw().get("security");

      if (explicit == null) {
        continue;
      }

      for (final var requirement : asList(explicit)) {
        assertThat(asMap(requirement).keySet())
            .as("%s references a declared scheme", operation.key())
            .isSubsetOf(schemes.keySet());
      }
    }
  }

  @Test
  @DisplayName("every operation is secured, or public on purpose, or readable anonymously")
  void everyOperationHasTheRightSecurity() throws IOException {
    final var doc = loadSpec();
    final var global = asList(doc.get("security"));
    final var routes = this.panelRoutes();
    final var findings = new TreeSet<String>();
    final var publicIds = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      final var access = accessOf(operation.raw(), global);
      final var handlers =
          routes.stream().filter(route -> route.key().equals(operation.key())).toList();

      if (access == Access.PUBLIC) {
        publicIds.add(operation.id());
      }

      if (handlers.isEmpty()) {
        continue;
      }

      // A route that reads repo access from @RepoOperation is readable anonymously only for READ,
      // and only where a {repoName} names a repo that can be public.
      final var anonymousRead =
          handlers.stream()
              .allMatch(
                  route ->
                      route.repoOperation() != null
                          && route.repoOperation().permission() == Permission.READ
                          && route.variables().contains("repoName"));
      final var demandsAuthorization =
          handlers.stream().anyMatch(RouteInfo::readsAuthorizationHeader);
      final var expected = anonymousRead ? Access.OPTIONAL : Access.REQUIRED;

      if (PUBLIC_OPERATIONS.contains(operation.id())) {
        if (access != Access.PUBLIC) {
          findings.add(operation.key() + " (" + operation.id() + ") must be `security: []`");
        }
        if (demandsAuthorization || handlers.stream().anyMatch(r -> r.repoOperation() != null)) {
          findings.add(operation.key() + " is public in the spec but its controller checks auth");
        }
      } else if (access != expected) {
        findings.add(
            operation.key()
                + " ("
                + operation.id()
                + "): spec says "
                + access
                + ", the controller needs "
                + expected);
      }
    }

    assertThat(publicIds).as("the public operations").isEqualTo(PUBLIC_OPERATIONS);
    assertNoNewFindings("operation security", findings, Map.of());
  }

  @Test
  @DisplayName("every operation that is not public documents 401")
  void everyProtectedOperationDocuments401() throws IOException {
    final var doc = loadSpec();
    final var global = asList(doc.get("security"));
    final var findings = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      if (accessOf(operation.raw(), global) == Access.PUBLIC) {
        continue;
      }

      final var codes =
          asMap(operation.raw().get("responses")).keySet().stream()
              .map(String::valueOf)
              .collect(Collectors.toSet());

      if (!codes.contains("401")) {
        findings.add(operation.key() + " (" + operation.id() + ")");
      }
    }

    assertNoNewFindings("operations without a 401", findings, Map.of());
  }

  @Test
  @DisplayName("every operation that needs MANAGE documents 403 next to 401 (RPS-1284)")
  void everyManageOperationDocuments403() throws IOException {
    final var doc = loadSpec();
    final var routes = this.panelRoutes();
    final var findings = new TreeSet<String>();
    var manageOperations = 0;

    for (final var operation : specOperations(doc).values()) {
      final var handlers =
          routes.stream().filter(route -> route.key().equals(operation.key())).toList();

      if (handlers.isEmpty()
          || !handlers.stream()
              .allMatch(
                  route ->
                      route.repoOperation() != null
                          && route.repoOperation().permission() == Permission.MANAGE)) {
        continue;
      }

      manageOperations++;

      final var codes =
          asMap(operation.raw().get("responses")).keySet().stream()
              .map(String::valueOf)
              .collect(Collectors.toSet());

      if (!codes.contains("403")) {
        findings.add(operation.key() + " (" + operation.id() + ")");
      }
    }

    assertThat(manageOperations).as("operations that need MANAGE").isGreaterThan(30);
    assertNoNewFindings("MANAGE operations without a 403", findings, Map.of());
  }

  @Test
  @DisplayName("every documented 403 belongs to a MANAGE or an admin-only operation (RPS-1593)")
  void everyDocumented403IsManageOrAdmin() throws IOException {
    final var doc = loadSpec();
    final var routes = this.panelRoutes();
    final var findings = new TreeSet<String>();
    final var operations = specOperations(doc);
    var forbidden = 0;

    for (final var operation : operations.values()) {
      final var codes =
          asMap(operation.raw().get("responses")).keySet().stream()
              .map(String::valueOf)
              .collect(Collectors.toSet());

      if (!codes.contains("403")) {
        continue;
      }

      forbidden++;

      final var handlers =
          routes.stream().filter(route -> route.key().equals(operation.key())).toList();
      final var manage =
          !handlers.isEmpty()
              && handlers.stream()
                  .allMatch(
                      route ->
                          route.repoOperation() != null
                              && route.repoOperation().permission() == Permission.MANAGE);

      if (!manage && !ADMIN_OPERATIONS.contains(operation.id())) {
        findings.add(
            operation.key()
                + " ("
                + operation.id()
                + "): documents 403 but is neither @RepoOperation(MANAGE) nor listed in"
                + " ADMIN_OPERATIONS");
      }
    }

    for (final var id : ADMIN_OPERATIONS) {
      final var operation =
          operations.values().stream().filter(candidate -> candidate.id().equals(id)).findFirst();

      if (operation.isEmpty()) {
        findings.add(id + ": listed in ADMIN_OPERATIONS but not in the spec");
        continue;
      }

      final var codes =
          asMap(operation.get().raw().get("responses")).keySet().stream()
              .map(String::valueOf)
              .collect(Collectors.toSet());
      final var handlers =
          routes.stream().filter(route -> route.key().equals(operation.get().key())).toList();

      if (!codes.contains("403")) {
        findings.add(id + ": admin-only but the spec does not document 403");
      }
      if (handlers.isEmpty()) {
        findings.add(id + ": listed in ADMIN_OPERATIONS but no panel handler serves it");
      }
      if (handlers.stream()
          .anyMatch(
              route ->
                  route.repoOperation() != null
                      && route.repoOperation().permission() == Permission.MANAGE)) {
        findings.add(id + ": listed in ADMIN_OPERATIONS but its handler is @RepoOperation(MANAGE)");
      }
    }

    // The same floor as the e2e role sweep, so a parser bug cannot empty the check.
    assertThat(forbidden).as("operations that document 403").isGreaterThanOrEqualTo(47);
    assertNoNewFindings("documented 403 without MANAGE or admin", findings, Map.of());
  }

  @Test
  @DisplayName("the query parameters of an operation are the ones its controller binds (RPS-1269)")
  void queryParametersMatchTheController() throws IOException {
    final var doc = loadSpec();
    final var routes = this.panelRoutes();
    final var findings = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      final var handlers =
          routes.stream().filter(route -> route.key().equals(operation.key())).toList();

      if (handlers.isEmpty()) {
        continue;
      }

      // One (method, path) can be served by several handlers that differ in a required parameter
      // (a list with and without its filter), so the spec documents the union of what they bind.
      final var bound = new TreeSet<String>();
      handlers.forEach(route -> bound.addAll(route.boundQueryParameters()));

      final var documented = new TreeSet<String>();

      for (final var parameter : operation.parameters(doc)) {
        if ("query".equals(parameter.get("in"))) {
          documented.add(String.valueOf(parameter.get("name")));
        }
      }

      if (!documented.equals(bound)) {
        findings.add(
            operation.key()
                + ": spec documents "
                + documented
                + " but the controller binds "
                + bound);
      }
    }

    assertNoNewFindings("query parameters", findings, Map.of());
  }

  @Test
  @DisplayName("no panel schema or parameter is named in snake_case")
  void panelNamesAreCamelCase() throws IOException {
    final var doc = loadSpec();

    assertNoNewFindings(
        "non-camelCase panel names", nonCamelCaseNames(doc, WIRE_SHARED_PROPERTIES), Map.of());
  }

  /**
   * Flip-and-fail: the rule sees a snake_case property, however deep, and a snake_case parameter.
   */
  @Test
  @DisplayName("the camelCase rule fires on a snake_case property and parameter")
  void camelCaseRuleFires() throws IOException {
    final var doc = loadSpec();
    final var schemas = asMap(asMap(doc.get("components")).get("schemas"));

    // a property of a schema the panel reaches through a response...
    asMap(asMap(schemas.get("DeployTokenInfoListItem")).get("properties"))
        .put("read_only_flag", Map.of("type", "boolean"));
    // ...and one nested in a schema that only a list wrapper references
    asMap(asMap(schemas.get("CrateDependencyInfo")).get("properties"))
        .put("default_features", Map.of("type", "boolean"));

    final var listUsers = asMap(asMap(asMap(doc.get("paths")).get("/api/users")).get("get"));
    asList(listUsers.get("parameters"))
        .add(Map.of("name", "sort_by", "in", "query", "schema", Map.of("type", "string")));

    assertThat(nonCamelCaseNames(doc, Set.of()))
        .contains(
            "DeployTokenInfoListItem.read_only_flag",
            "CrateDependencyInfo.default_features",
            "GET /api/users parameter sort_by");
    assertThat(nonCamelCaseNames(doc, Set.of("DeployTokenInfoListItem.read_only_flag")))
        .doesNotContain("DeployTokenInfoListItem.read_only_flag");
  }

  @Test
  @DisplayName("every $ref in the spec resolves")
  void referencesResolve() throws IOException {
    final var doc = loadSpec();
    final var findings = new TreeSet<String>();

    collectBrokenRefs(doc, doc, "#", findings);

    assertNoNewFindings("unresolved references", findings, Map.of());
  }

  @Test
  @DisplayName("ProblemDetail lists the members of the problem the error handler really writes")
  void problemDetailIsTheRealProblem() throws Exception {
    final var doc = loadSpec();
    final var schema =
        asMap(asMap(asMap(doc.get("components")).get("schemas")).get("ProblemDetail"));

    final var result =
        this.perform(MockMvcRequestBuilders.get("/api/users").param("page", "-1")).andReturn();
    final Map<String, Object> problem =
        JsonPath.read(result.getResponse().getContentAsString(), "$");

    assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
    assertThat(asMap(schema.get("properties")).keySet())
        .containsExactlyInAnyOrder(
            "type", "title", "status", "detail", "instance", "code", "traceId", "errors");
    assertThat(asMap(schema.get("properties")).keySet()).containsAll(problem.keySet());
    assertThat(problem).containsEntry("code", "validationError");
  }

  @Test
  @DisplayName("the Created, NoContent and Accepted success components have their shape")
  void successResponseComponentsHaveTheirShape() throws IOException {
    final var responses = asMap(asMap(loadSpec().get("components")).get("responses"));

    SUCCESS_RESPONSES.forEach(
        name -> assertThat(responses).as("components/responses/" + name).containsKey(name));

    final var created = asMap(responses.get("Created"));
    final var accepted = asMap(responses.get("Accepted"));
    final var noContent = asMap(responses.get("NoContent"));

    assertThat(asMap(created.get("headers"))).containsKey("Location");
    assertThat(asMap(accepted.get("headers"))).containsKey("Location");
    assertThat(noContent).doesNotContainKeys("content", "headers");
    SUCCESS_RESPONSES.forEach(
        name ->
            assertThat(asMap(responses.get(name)).get("content"))
                .as(name + " has no problem+json body")
                .isNull());
  }

  @Test
  @DisplayName("every documented 4xx and 5xx response is a shared problem response")
  void everyErrorResponseIsTheSharedProblem() throws IOException {
    final var doc = loadSpec();
    final var findings = new TreeSet<String>();

    specOperations(doc)
        .forEach(
            (key, operation) ->
                asMap(operation.raw().get("responses"))
                    .forEach(
                        (status, response) -> {
                          final var code = String.valueOf(status);
                          if (!code.startsWith("4") && !code.startsWith("5")) {
                            return;
                          }
                          final var ref = asMap(response).get("$ref");
                          if (ref == null && !asMap(response).containsKey("content")) {
                            return; // a deliberately empty body, such as the Go sumdb 404
                          }
                          if (ref == null
                              || !String.valueOf(ref).startsWith("#/components/responses/")) {
                            findings.add(key + " " + code + " is not a shared response");
                          }
                        }));

    final var responses = asMap(asMap(doc.get("components")).get("responses"));
    responses.forEach(
        (name, response) -> {
          if (SUCCESS_RESPONSES.contains(String.valueOf(name))) {
            return; // success components are checked by successResponseComponentsHaveTheirShape
          }
          final var content = asMap(asMap(response).get("content"));
          if (!content.containsKey("application/problem+json")
              || content.size() != 1
              || !"#/components/schemas/ProblemDetail"
                  .equals(
                      asMap(asMap(content.get("application/problem+json")).get("schema"))
                          .get("$ref"))) {
            findings.add("components/responses/" + name + " is not a ProblemDetail response");
          }
        });

    assertNoNewFindings("error responses that are not the shared problem", findings, Map.of());
  }

  @Test
  @DisplayName("the RepoType schema lists exactly the values of the RepoType enum, upper case")
  void repoTypeSchemaIsTheJavaEnum() throws Exception {
    final var doc = loadSpec();
    final var schema = asMap(asMap(asMap(doc.get("components")).get("schemas")).get("RepoType"));

    assertThat(schema).containsEntry("type", "string");
    assertThat(asList(schema.get("enum")))
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(RepoType.values()).map(RepoType::name).collect(Collectors.toList()));
  }

  // ---------------------------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------------------------

  /**
   * The properties of every schema reachable from an operation, and the query and path parameters
   * of every operation, whose name is not camelCase, minus the allowed wire-shared ones.
   */
  private static Set<String> nonCamelCaseNames(
      final Map<String, Object> doc, final Set<String> allowed) {

    final var findings = new TreeSet<String>();
    final var visited = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      for (final var parameter : operation.parameters(doc)) {
        final var location = String.valueOf(parameter.get("in"));
        final var name = String.valueOf(parameter.get("name"));

        if (!"header".equals(location)
            && !"cookie".equals(location)
            && !CAMEL_CASE.matcher(name).matches()) {
          findings.add(operation.method() + " " + operation.template() + " parameter " + name);
        }
      }

      collectNonCamelCaseProperties(operation.raw(), doc, operation.id(), visited, findings);
    }

    findings.removeAll(allowed);

    return findings;
  }

  /**
   * Walks a node of the spec, following every {@code $ref} into {@code components} once, and
   * records each key of a {@code properties} map that is not camelCase as {@code Schema.property}.
   */
  private static void collectNonCamelCaseProperties(
      final Object node,
      final Map<String, Object> doc,
      final String schema,
      final Set<String> visited,
      final Set<String> findings) {

    if (node instanceof final Map<?, ?> map) {
      final var ref = map.get("$ref");

      if (ref instanceof final String target && resolves(doc, target)) {
        if (visited.add(target)) {
          Object referenced = doc;

          for (final var part : target.substring(2).split("/")) {
            referenced = asMap(referenced).get(part);
          }

          final var parts = target.split("/");
          final var owner = target.startsWith("#/components/schemas/") ? parts[3] : schema;

          collectNonCamelCaseProperties(referenced, doc, owner, visited, findings);
        }

        return;
      }

      if (map.get("properties") instanceof final Map<?, ?> properties) {
        properties.keySet().stream()
            .map(String::valueOf)
            .filter(name -> !CAMEL_CASE.matcher(name).matches())
            .forEach(name -> findings.add(schema + "." + name));
      }

      map.values()
          .forEach(value -> collectNonCamelCaseProperties(value, doc, schema, visited, findings));
    } else if (node instanceof final Collection<?> items) {
      items.forEach(item -> collectNonCamelCaseProperties(item, doc, schema, visited, findings));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tags (RPS-1897)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("every operation has exactly one tag, a clean domain noun from the tag table")
  void tagsAreCleanAndFromTheTable() throws IOException {
    final var doc = loadSpec();
    final var table = loadTagTable();

    assertNoNewFindings("operation tags", tagFindings(doc, allowedTags(table)), Map.of());
  }

  @Test
  @DisplayName("the tag table is consistent and describes the OS spec")
  void tagTableIsConsistent() throws IOException {
    final var doc = loadSpec();
    final var table = loadTagTable();
    final var allowed = allowedTags(table);
    final var findings = new TreeSet<String>();

    for (final var section : List.of("os", "cloud", "cloudAdmin")) {
      asMap(table.get(section))
          .forEach(
              (oldTag, newTag) -> {
                if (!String.valueOf(oldTag).endsWith(CONTROLLER_SUFFIX)) {
                  findings.add(section + ": old tag " + oldTag + " has no -controller suffix");
                }
                if (!allowed.contains(String.valueOf(newTag))) {
                  findings.add(section + ": " + oldTag + " maps to unlisted " + newTag);
                }
              });
    }

    for (final var tag : allowed) {
      if (!KEBAB_CASE.matcher(tag).matches() || tag.endsWith(CONTROLLER_SUFFIX)) {
        findings.add("allowed tag " + tag + " is not a kebab-case noun without -controller");
      }
    }

    final var osTags = new TreeSet<String>();
    asMap(table.get("os")).values().forEach(v -> osTags.add(String.valueOf(v)));
    final var used = new TreeSet<String>();
    specOperations(doc).values().forEach(op -> used.addAll(tagsOf(op)));

    if (!osTags.equals(used)) {
      findings.add("the os mapping yields " + osTags + " but the spec uses " + used);
    }

    assertNoNewFindings("the tag table", findings, Map.of());
  }

  /** Flip-and-fail: the tag rules see a -controller tag, no tag, two tags and an unlisted tag. */
  @Test
  @DisplayName(
      "the tag rules fire on a -controller tag, a missing tag, two tags and an unlisted tag")
  void tagRulesFire() throws IOException {
    final var doc = loadSpec();
    final var allowed = allowedTags(loadTagTable());
    final var paths = asMap(doc.get("paths"));

    asMap(asMap(paths.get("/api/users")).get("get")).put("tags", List.of("user-controller"));
    asMap(asMap(paths.get("/api/users")).get("post")).remove("tags");
    asMap(asMap(paths.get("/api/usage")).get("get")).put("tags", List.of("usage", "users"));
    asMap(asMap(paths.get("/api/auth/login")).get("post")).put("tags", List.of("authentication"));

    assertThat(tagFindings(doc, allowed))
        .contains(
            "GET /api/users has a tag ending in -controller: user-controller",
            "POST /api/users has no tag",
            "GET /api/usage has 2 tags: [usage, users]",
            "POST /api/auth/login has a tag outside the table: authentication");
  }

  private static Set<String> tagFindings(final Map<String, Object> doc, final Set<String> allowed) {
    final var findings = new TreeSet<String>();

    specOperations(doc)
        .forEach(
            (key, operation) -> {
              final var tags = tagsOf(operation);

              if (tags.isEmpty()) {
                findings.add(key + " has no tag");
                return;
              }
              if (tags.size() > 1) {
                findings.add(key + " has " + tags.size() + " tags: " + tags);
              }
              for (final var tag : tags) {
                if (tag.endsWith(CONTROLLER_SUFFIX)) {
                  findings.add(key + " has a tag ending in -controller: " + tag);
                } else if (!allowed.contains(tag)) {
                  findings.add(key + " has a tag outside the table: " + tag);
                }
              }
            });

    return findings;
  }

  private static List<String> tagsOf(final SpecOperation operation) {
    final var tags = operation.raw().get("tags");

    return tags == null ? List.of() : asList(tags).stream().map(String::valueOf).toList();
  }

  private static Set<String> allowedTags(final Map<String, Object> table) {
    return asList(table.get("allowedTags")).stream()
        .map(String::valueOf)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Map<String, Object> loadTagTable() throws IOException {
    try (var in = new ClassPathResource(TAG_TABLE_RESOURCE).getInputStream()) {
      // JSON is YAML; the spec loader's parser reads it.
      return asMap(new Yaml(new SafeConstructor(new LoaderOptions())).load(in));
    }
  }

  private static Map<String, Object> loadSpec() throws IOException {
    final var options = new LoaderOptions();
    // A key written twice in one mapping is silently last-wins for a parser that allows it, and
    // the OpenAPI generator rejects the whole spec (an operation with two descriptions).
    options.setAllowDuplicateKeys(false);

    try (var in = new ClassPathResource(SPEC_RESOURCE).getInputStream()) {
      return asMap(new Yaml(new SafeConstructor(options)).load(in));
    }
  }

  private static Map<String, SpecOperation> specOperations(final Map<String, Object> doc) {
    final var operations = new TreeMap<String, SpecOperation>();

    asMap(doc.get("paths"))
        .forEach(
            (template, item) ->
                asMap(item)
                    .forEach(
                        (method, operation) -> {
                          if (HTTP_METHODS.contains(method)) {
                            final var parsed =
                                new SpecOperation(
                                    method.toUpperCase(Locale.ROOT),
                                    template,
                                    asMap(item),
                                    asMap(operation));
                            operations.put(parsed.key(), parsed);
                          }
                        }));

    return operations;
  }

  private List<RouteInfo> panelRoutes() {
    final var routes = new ArrayList<RouteInfo>();

    for (final var entry : this.handlerMapping.getHandlerMethods().entrySet()) {
      final var info = entry.getKey();
      final var handler = entry.getValue();

      if (!isOnTheApiPort(handler)) {
        continue;
      }

      for (final var pattern : info.getPatternValues()) {
        if (!pattern.startsWith(PANEL_PREFIX)) {
          continue;
        }

        final var methods = info.getMethodsCondition().getMethods();

        assertThat(methods).as("HTTP methods of %s %s", handler, pattern).isNotEmpty();

        for (final var method : methods) {
          routes.add(new RouteInfo(method.name(), pattern, handler));
        }
      }
    }

    return routes;
  }

  private static boolean isOnTheApiPort(final HandlerMethod handler) {
    return AnnotationUtils.findAnnotation(handler.getMethod(), RestApiPort.class) != null
        || AnnotationUtils.findAnnotation(handler.getBeanType(), RestApiPort.class) != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Rules
  // ---------------------------------------------------------------------------------------------

  /**
   * Records a clash for two templates of the same length that share every segment before one
   * position and put different variables there. A literal on either side is not a clash, and
   * templates of other lengths never compete.
   */
  private static void collectClash(
      final String first, final String second, final Map<String, Set<String>> names) {

    final var left = first.split("/");
    final var right = second.split("/");

    if (left.length != right.length) {
      return;
    }

    for (int i = 0; i < left.length; i++) {
      if (left[i].equals(right[i])) {
        continue;
      }

      final var leftVariable = VARIABLE.matcher(left[i]);
      final var rightVariable = VARIABLE.matcher(right[i]);

      if (leftVariable.matches() && rightVariable.matches()) {
        final var prefix = String.join("/", Arrays.copyOfRange(left, 0, i)) + "/";
        final var found = names.computeIfAbsent(prefix, _ -> new TreeSet<>());
        found.add("{" + leftVariable.group(1) + "}");
        found.add("{" + rightVariable.group(1) + "}");
      }

      return;
    }
  }

  private static boolean isReservedRepoName(final String name) {
    try {
      RepoUtils.validateNewRepoName(name);
      return false;
    } catch (final BadRequestException _) {
      return true;
    } catch (final RuntimeException _) {
      return false;
    }
  }

  private static Access accessOf(final Map<String, Object> operation, final List<Object> global) {
    final var requirements =
        operation.containsKey("security") ? asList(operation.get("security")) : global;

    if (requirements.isEmpty()) {
      return Access.PUBLIC;
    }

    if (requirements.stream().anyMatch(requirement -> asMap(requirement).isEmpty())) {
      return Access.OPTIONAL;
    }

    return Access.REQUIRED;
  }

  private static void collectBrokenRefs(
      final Object node, final Map<String, Object> doc, final String at, final Set<String> found) {

    if (node instanceof final Map<?, ?> map) {
      final var ref = map.get("$ref");

      if (ref instanceof final String target && !resolves(doc, target)) {
        found.add(at + " -> " + target);
      }

      map.forEach((key, value) -> collectBrokenRefs(value, doc, at + "/" + key, found));
    } else if (node instanceof final Collection<?> items) {
      int index = 0;

      for (final var item : items) {
        collectBrokenRefs(item, doc, at + "/" + index++, found);
      }
    }
  }

  private static boolean resolves(final Map<String, Object> doc, final String ref) {
    if (!ref.startsWith("#/")) {
      return false;
    }

    Object node = doc;

    for (final var part : ref.substring(2).split("/")) {
      if (!(node instanceof final Map<?, ?> map) || !map.containsKey(part)) {
        return false;
      }

      node = map.get(part);
    }

    return true;
  }

  /**
   * Fails for a finding that is not known, and for a known one that no longer occurs, so the list
   * of known drift can only shrink.
   */
  private static void assertNoNewFindings(
      final String what, final Set<String> found, final Map<String, String> known) {

    final var unknown = new TreeSet<>(found);
    unknown.removeAll(known.keySet());

    final var stale = new TreeSet<>(known.keySet());
    stale.removeAll(found);

    assertThat(unknown)
        .as(
            "New drift in %s. Fix the spec or the controller; do not add it to the known list.",
            what)
        .isEmpty();
    assertThat(stale)
        .as("Known drift in %s that is gone: remove it from the KNOWN_* map", what)
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Status codes and body presence (RPS-1886)
  // ---------------------------------------------------------------------------------------------

  /**
   * RPS-1886: known drift between a handler and the spec's success responses. Empty means none; an
   * entry carries the reason.
   */
  private static final Map<String, String> KNOWN_RESPONSE_DRIFT = Map.of();

  /**
   * RPS-1886: mappings that are deliberately in no panel spec, by {@code Controller.method}: the
   * wire protocols, the SPA forwarding and the framework's own endpoints. The protocol routes keep
   * their own error formats and are documented by the protocol, not by the panel spec.
   */
  private static final Map<String, String> OUTSIDE_THE_PANEL_SPEC =
      Map.of(
          "BasicErrorController.error", "Spring Boot's /error page, not a panel route",
          "BasicErrorController.errorHtml", "Spring Boot's /error page, not a panel route",
          "SpaController.forward",
              "the panel's SPA forwarding, serves index.html, not an API route",
          "OpenApiWebMvcResource.openapiJson", "springdoc's generated /v3/api-docs, not the spec",
          "OpenApiWebMvcResource.openapiYaml",
              "springdoc's generated /v3/api-docs.yaml, not the spec",
          "SwaggerConfigResource.openapiJson", "springdoc's swagger-ui configuration",
          "SwaggerWelcomeWebMvc.redirectToUi", "springdoc's redirect to the Swagger UI",
          "ProtocolRouterController.route",
              "the catch-all of every wire protocol (npm, Maven, OCI, ...), which has its own"
                  + " formats and is not part of the panel spec");

  @Test
  @DisplayName("the success responses of an operation match what its handler returns")
  void successResponsesMatchTheHandlers() throws IOException {
    final var findings = responseFindings(loadSpec(), this.panelRoutes());

    assertNoNewFindings("success status and body", findings, KNOWN_RESPONSE_DRIFT);
  }

  /**
   * Flip-and-fail: a handler that returns no body next to a spec 2xx that promises one, a handler
   * that returns a body next to a spec that has none (or a 204), and a 201 the spec does not
   * declare.
   */
  @Test
  @DisplayName("the response rule fires on a body mismatch, a 204 with a body and a missing 201")
  void responseRuleFires() throws IOException {
    final var doc = loadSpec();
    final var routes = this.panelRoutes();
    final var operations = specOperations(doc);

    // a void handler whose spec promises a body
    final var noBody =
        operations.values().stream()
            .filter(op -> returnShapeOf(routes, op.key()) == Shape.NONE)
            .filter(op -> successCodes(op).contains("204"))
            .findFirst()
            .orElseThrow();
    asMap(noBody.raw().get("responses"))
        .put(
            "204",
            Map.of(
                "description",
                "x",
                "content",
                Map.of("application/json", Map.of("schema", Map.of("type", "string")))));

    // a body-returning handler whose spec has no content, and one whose spec says 204
    final var withBody =
        operations.values().stream()
            .filter(op -> returnShapeOf(routes, op.key()) == Shape.BODY)
            .filter(op -> successCodes(op).contains("200"))
            .toList();
    asMap(asMap(withBody.get(0).raw().get("responses")).get("200")).remove("content");
    asMap(withBody.get(1).raw().get("responses")).put("204", Map.of("description", "x"));

    // a handler that answers 201 whose spec lost the 201
    final var created =
        operations.values().stream()
            .filter(op -> inferredCodesOf(routes, op.key()).contains(201))
            .findFirst()
            .orElseThrow();
    asMap(created.raw().get("responses")).remove("201");

    final var findings = responseFindings(doc, routes);

    assertThat(findings)
        .anyMatch(f -> f.startsWith(noBody.key()) && f.contains("returns no body"))
        .anyMatch(f -> f.startsWith(withBody.get(0).key()) && f.contains("has no content"))
        .anyMatch(f -> f.startsWith(withBody.get(1).key()) && f.contains("declares 204"))
        .anyMatch(f -> f.startsWith(created.key()) && f.contains("does not declare 201"));
  }

  private static Shape returnShapeOf(final List<RouteInfo> routes, final String key) {
    return routes.stream()
        .filter(route -> route.key().equals(key))
        .map(RouteInfo::returnShape)
        .findFirst()
        .orElse(Shape.UNKNOWN);
  }

  private static Set<Integer> inferredCodesOf(final List<RouteInfo> routes, final String key) {
    return routes.stream()
        .filter(route -> route.key().equals(key))
        .flatMap(route -> route.inferredSuccessCodes().stream())
        .collect(Collectors.toSet());
  }

  private static Set<String> successCodes(final SpecOperation operation) {
    return asMap(operation.raw().get("responses")).keySet().stream()
        .map(String::valueOf)
        .filter(code -> code.startsWith("2"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Compares the 2xx responses of every spec operation with what each of its handlers returns: a
   * {@code void} or {@code ResponseEntity<Void>} handler has no body, so no 2xx may promise
   * content; any other handler has one, so a 2xx must carry content and 204 is not declared; and
   * every 201, 202 or 204 the handler is seen to produce ({@code ResponseEntities.created}, {@code
   * accepted}, {@code noContent}, {@code ResponseEntity.created/accepted/noContent}, {@code
   * HttpStatus.CREATED...}, {@code @ResponseStatus}) is a code the spec declares. A handler that
   * delegates its status to a callee is not seen, so the status direction is one-way.
   */
  private static Set<String> responseFindings(
      final Map<String, Object> doc, final List<RouteInfo> routes) {

    final var findings = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      final var responses = asMap(operation.raw().get("responses"));
      final var codes = successCodes(operation);

      if (codes.isEmpty()) {
        findings.add(operation.key() + ": no 2xx response declared");
      }

      final var hasContent = new TreeSet<String>();

      for (final var code : codes) {
        if (deref(doc, responses.get(code)).containsKey("content")) {
          hasContent.add(code);
        }
      }

      for (final var route : routes) {
        if (!route.key().equals(operation.key())) {
          continue;
        }

        final var name = handlerName(route.handler());

        switch (route.returnShape()) {
          case NONE -> {
            if (!hasContent.isEmpty()) {
              findings.add(
                  operation.key()
                      + ": "
                      + name
                      + " returns no body but the spec promises one in "
                      + hasContent);
            }
          }
          case BODY -> {
            if (hasContent.isEmpty()) {
              findings.add(
                  operation.key()
                      + ": "
                      + name
                      + " returns a body but the spec 2xx "
                      + codes
                      + " has no content");
            }

            if (codes.contains("204")) {
              findings.add(
                  operation.key() + ": " + name + " returns a body but the spec declares 204");
            }
          }
          case UNKNOWN -> {
            // ResponseEntity<?> or Object: the body cannot be derived from the signature
          }
        }

        for (final var inferred : route.inferredSuccessCodes()) {
          if (!codes.contains(String.valueOf(inferred))) {
            findings.add(
                operation.key()
                    + ": "
                    + name
                    + " answers "
                    + inferred
                    + " but the spec does not declare "
                    + inferred
                    + " (declares "
                    + codes
                    + ")");
          }
        }
      }
    }

    return findings;
  }

  /**
   * RPS-1959: operations whose 2xx body may be a {@code RestResponse} envelope, by operation key,
   * with the reason. Empty: every panel success body is the bare resource (API guideline, Decision
   * 5). The wire-protocol routes are not in the panel spec at all, so they need no entry.
   */
  private static final Map<String, String> ENVELOPED_SUCCESS_ALLOWED = Map.of();

  @Test
  @DisplayName("no 2xx body of the panel spec is a RestResponse envelope")
  void noSuccessBodyIsAnEnvelope() throws IOException {
    final var findings = envelopeFindings(loadSpec(), ENVELOPED_SUCCESS_ALLOWED.keySet());

    assertThat(findings).as("enveloped 2xx bodies").isEmpty();
  }

  /** Flip-and-fail: an envelope schema on a 2xx fails, and the allow-list is the only way out. */
  @Test
  @DisplayName(
      "the envelope rule fires on RestResponse and msgId bodies and honours the allow-list")
  void envelopeRuleFires() throws IOException {
    final var doc = loadSpec();
    final var operations = specOperations(doc).values().stream().toList();
    final var byName = operations.get(0);
    final var byShape = operations.get(1);
    final var inline = operations.get(2);
    final var schemas = asMap(asMap(doc.get("components")).get("schemas"));

    schemas.put("RestResponseFlip", Map.of("type", "object"));
    asMap(byName.raw().get("responses"))
        .put(
            "200",
            Map.of(
                "description",
                "x",
                "content",
                Map.of(
                    "application/json",
                    Map.of("schema", Map.of("$ref", "#/components/schemas/RestResponseFlip")))));

    schemas.put(
        "Wrapped",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("msgId", Map.of("type", "string"), "type", Map.of("type", "string"))));
    asMap(byShape.raw().get("responses"))
        .put(
            "200",
            Map.of(
                "description",
                "x",
                "content",
                Map.of(
                    "application/json",
                    Map.of("schema", Map.of("$ref", "#/components/schemas/Wrapped")))));

    asMap(inline.raw().get("responses"))
        .put(
            "200",
            Map.of(
                "description",
                "x",
                "content",
                Map.of(
                    "application/json",
                    Map.of(
                        "schema",
                        Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("msgId", Map.of("type", "string")))))));

    assertThat(envelopeFindings(doc, Set.of()))
        .anyMatch(f -> f.startsWith(byName.key()))
        .anyMatch(f -> f.startsWith(byShape.key()))
        .anyMatch(f -> f.startsWith(inline.key()));
    assertThat(envelopeFindings(doc, Set.of(byName.key(), byShape.key(), inline.key()))).isEmpty();
  }

  /**
   * The 2xx responses whose body schema is an envelope: a {@code RestResponse*} or {@code
   * EmptyResponse} schema, or any schema (also inside {@code allOf}, {@code items}) that has a
   * {@code msgId} property.
   */
  private static Set<String> envelopeFindings(
      final Map<String, Object> doc, final Set<String> allowed) {

    final var findings = new TreeSet<String>();

    for (final var operation : specOperations(doc).values()) {
      if (allowed.contains(operation.key())) {
        continue;
      }

      final var responses = asMap(operation.raw().get("responses"));

      for (final var code : successCodes(operation)) {
        final var content = mapOrEmpty(deref(doc, responses.get(code)).get("content"));

        for (final var media : content.values()) {
          final var schema = mapOrEmpty(asMap(media).get("schema"));

          if (isEnvelope(doc, schema, 0)) {
            findings.add(operation.key() + ": " + code + " body is an envelope");
          }
        }
      }
    }

    return findings;
  }

  private static Map<String, Object> mapOrEmpty(final Object value) {
    return value == null ? Map.of() : asMap(value);
  }

  private static boolean isEnvelope(
      final Map<String, Object> doc, final Map<String, Object> schema, final int depth) {

    if (depth > 6) {
      return false;
    }

    if (schema.get("$ref") instanceof final String ref) {
      final var name = ref.substring(ref.lastIndexOf('/') + 1);

      if (name.startsWith("RestResponse") || name.equals("EmptyResponse")) {
        return true;
      }

      return resolves(doc, ref) && isEnvelope(doc, deref(doc, schema), depth + 1);
    }

    if (mapOrEmpty(schema.get("properties")).containsKey("msgId")) {
      return true;
    }

    if (isEnvelope(doc, mapOrEmpty(schema.get("items")), depth + 1)) {
      return true;
    }

    for (final var part : schema.get("allOf") instanceof final List<?> parts ? parts : List.of()) {
      if (isEnvelope(doc, asMap(part), depth + 1)) {
        return true;
      }
    }

    return false;
  }

  /** Follows a {@code $ref} (a shared response component) to the node it names. */
  private static Map<String, Object> deref(final Map<String, Object> doc, final Object node) {
    var current = asMap(node);

    while (current.get("$ref") instanceof final String target && resolves(doc, target)) {
      Object next = doc;

      for (final var part : target.substring(2).split("/")) {
        next = asMap(next).get(part);
      }

      current = asMap(next);
    }

    return current;
  }

  private static String handlerName(final HandlerMethod handler) {
    return handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
  }

  // ---------------------------------------------------------------------------------------------
  // Every mapping is in the spec (RPS-1886)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("every controller mapping is in the spec, or deliberately outside it")
  void everyMappingIsInTheSpecOrAllowListed() throws IOException {
    final var spec = specOperations(loadSpec()).keySet();
    final var findings = new TreeSet<String>();
    final var used = new TreeSet<String>();

    for (final var finding : unspecifiedMappings(this.handlerMapping, spec)) {
      if (OUTSIDE_THE_PANEL_SPEC.containsKey(finding.handler())) {
        used.add(finding.handler());
      } else {
        findings.add(finding.toString());
      }
    }

    // a stale entry would let a renamed handler escape the rule
    final var stale = new TreeSet<>(OUTSIDE_THE_PANEL_SPEC.keySet());
    stale.removeAll(used);
    stale.forEach(name -> findings.add("OUTSIDE_THE_PANEL_SPEC lists no such mapping: " + name));

    assertNoNewFindings("controller mappings in no spec", findings, Map.of());
  }

  /** Flip-and-fail: a mapping whose spec operation was removed is reported. */
  @Test
  @DisplayName("the mapping rule fires on a path missing from the spec")
  void mappingRuleFires() throws IOException {
    final var someRoute = this.panelRoutes().get(0);
    final var all = new TreeSet<>(specOperations(loadSpec()).keySet());
    all.remove(someRoute.key());

    assertThat(unspecifiedMappings(this.handlerMapping, all))
        .extracting(Unspecified::key)
        .contains(someRoute.key());
  }

  /**
   * Every (method, pattern) of every handler of the application that is not a spec operation: a
   * panel route missing from the spec, or a mapping that is not a panel route at all (the protocol
   * router, the SPA routes, the error page).
   */
  private static List<Unspecified> unspecifiedMappings(
      final RequestMappingHandlerMapping mapping, final Set<String> specKeys) {

    final var found = new ArrayList<Unspecified>();

    for (final var entry : mapping.getHandlerMethods().entrySet()) {
      final var info = entry.getKey();
      final var handler = entry.getValue();
      final var methods =
          info.getMethodsCondition().getMethods().isEmpty()
              ? Set.of("ANY")
              : info.getMethodsCondition().getMethods().stream()
                  .map(Enum::name)
                  .collect(Collectors.toSet());

      for (final var pattern : info.getPatternValues()) {
        for (final var method : methods) {
          final var key = method + " " + normalize(pattern);

          if (!specKeys.contains(key)) {
            found.add(new Unspecified(handlerName(handler), key));
          }
        }
      }
    }

    return found;
  }

  private record Unspecified(String handler, String key) {

    @Override
    public String toString() {
      return "in a controller, in no spec: " + this.handler + " " + this.key;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Model
  // ---------------------------------------------------------------------------------------------

  /** What a handler returns, from its signature. */
  private enum Shape {
    NONE,
    BODY,
    UNKNOWN
  }

  private enum Access {
    PUBLIC,
    OPTIONAL,
    REQUIRED
  }

  /** One operation of the spec. */
  private record SpecOperation(
      String method, String template, Map<String, Object> pathItem, Map<String, Object> raw) {

    String key() {
      return this.method + " " + normalize(this.template);
    }

    String id() {
      return String.valueOf(this.raw.get("operationId"));
    }

    List<String> variables() {
      return variablesOf(this.template);
    }

    /** The parameters of the operation and of its path item, with references resolved. */
    List<Map<String, Object>> parameters(final Map<String, Object> doc) {
      final var all = new ArrayList<Map<String, Object>>();

      for (final var source : List.of(this.pathItem, this.raw)) {
        for (final var parameter : asList(source.getOrDefault("parameters", List.of()))) {
          final var map = asMap(parameter);
          final var ref = map.get("$ref");

          if (ref instanceof final String target && resolves(doc, target)) {
            Object node = doc;

            for (final var part : target.substring(2).split("/")) {
              node = asMap(node).get(part);
            }

            all.add(asMap(node));
          } else {
            all.add(map);
          }
        }
      }

      return all;
    }
  }

  /** One panel handler method mapped to one (method, path template). */
  private record RouteInfo(String method, String pattern, HandlerMethod handler) {

    String key() {
      return this.method + " " + normalize(this.pattern);
    }

    List<String> variables() {
      return variablesOf(this.pattern);
    }

    RepoOperation repoOperation() {
      return this.handler.getMethodAnnotation(RepoOperation.class);
    }

    boolean readsAuthorizationHeader() {
      return Arrays.stream(this.handler.getMethodParameters())
          .map(parameter -> parameter.getParameterAnnotation(RequestHeader.class))
          .filter(Objects::nonNull)
          .anyMatch(
              header ->
                  HttpHeaders.AUTHORIZATION.equalsIgnoreCase(
                      header.name().isEmpty() ? header.value() : header.name()));
    }

    /**
     * The query parameters the handler binds: its {@code @RequestParam} names, and {@code page},
     * {@code size} and {@code sort} for a Spring Data {@link Pageable}.
     */
    List<String> boundQueryParameters() {
      final var names = new ArrayList<String>();
      final var discoverer = new DefaultParameterNameDiscoverer();

      for (final var parameter : this.handler.getMethodParameters()) {
        if (Pageable.class.isAssignableFrom(parameter.getParameterType())) {
          names.addAll(List.of("page", "size", "sort"));
          continue;
        }

        final var annotation = parameter.getParameterAnnotation(RequestParam.class);

        if (annotation == null) {
          continue;
        }

        parameter.initParameterNameDiscovery(discoverer);

        final var explicit = annotation.name().isEmpty() ? annotation.value() : annotation.name();

        names.add(explicit.isEmpty() ? parameter.getParameterName() : explicit);
      }

      return names;
    }

    /** Whether the handler writes a body: {@code void} and {@code ResponseEntity<Void>} do not. */
    Shape returnShape() {
      var type = ResolvableType.forMethodReturnType(this.handler.getMethod());

      if (HttpEntity.class.isAssignableFrom(type.toClass())) {
        type = type.as(HttpEntity.class).getGeneric(0);
      }

      final var raw = type.resolve();

      if (raw == null || raw.equals(Object.class)) {
        return Shape.UNKNOWN;
      }

      return raw.equals(void.class) || raw.equals(Void.class) ? Shape.NONE : Shape.BODY;
    }

    /**
     * The 201, 202 and 204 the handler is seen to produce: {@code @ResponseStatus}, and calls of
     * {@code ResponseEntities.created/accepted/noContent} (or the {@code ResponseEntity} ones) and
     * reads of {@code HttpStatus.CREATED, ACCEPTED, NO_CONTENT} in its bytecode. A status chosen in
     * a callee is not seen.
     */
    Set<Integer> inferredSuccessCodes() {
      final var codes = new TreeSet<Integer>();
      final var method = this.handler.getMethod();
      final var annotated =
          AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);

      if (annotated != null
          && annotated.code().is2xxSuccessful()
          && annotated.code().value() != 200) {
        codes.add(annotated.code().value());
      }

      final var declaring = method.getDeclaringClass();
      final var descriptor = Type.getMethodDescriptor(method);

      try (var in =
          declaring.getResourceAsStream("/" + declaring.getName().replace('.', '/') + ".class")) {
        if (in == null) {
          return codes;
        }

        new ClassReader(in)
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  @Override
                  public MethodVisitor visitMethod(
                      final int access,
                      final String name,
                      final String desc,
                      final String signature,
                      final String[] exceptions) {

                    if (!name.equals(method.getName()) || !desc.equals(descriptor)) {
                      return null;
                    }

                    return new MethodVisitor(Opcodes.ASM9) {
                      @Override
                      public void visitMethodInsn(
                          final int opcode,
                          final String owner,
                          final String callee,
                          final String calleeDesc,
                          final boolean itf) {

                        if (owner.equals("org/springframework/http/ResponseEntity")
                            || owner.equals("io/repsy/core/web/http/ResponseEntities")) {
                          switch (callee) {
                            case "created" -> codes.add(201);
                            case "accepted" -> codes.add(202);
                            case "noContent" -> codes.add(204);
                            default -> {}
                          }
                        }
                      }

                      @Override
                      public void visitFieldInsn(
                          final int opcode,
                          final String owner,
                          final String field,
                          final String d) {

                        if (owner.equals("org/springframework/http/HttpStatus")) {
                          switch (field) {
                            case "CREATED" -> codes.add(201);
                            case "ACCEPTED" -> codes.add(202);
                            case "NO_CONTENT" -> codes.add(204);
                            default -> {}
                          }
                        }
                      }
                    };
                  }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      } catch (final IOException e) {
        throw new java.io.UncheckedIOException(e);
      }

      return codes;
    }

    /** The names the handler's {@code @PathVariable} parameters bind. */
    List<String> boundPathVariables() {
      final var names = new ArrayList<String>();
      final var discoverer = new DefaultParameterNameDiscoverer();

      for (final var parameter : this.handler.getMethodParameters()) {
        final var annotation = parameter.getParameterAnnotation(PathVariable.class);

        if (annotation == null) {
          continue;
        }

        parameter.initParameterNameDiscovery(discoverer);

        final var explicit = annotation.name().isEmpty() ? annotation.value() : annotation.name();

        names.add(explicit.isEmpty() ? parameter.getParameterName() : explicit);
      }

      return names;
    }
  }

  private static String normalize(final String template) {
    return VARIABLE.matcher(template).replaceAll("{}");
  }

  private static List<String> variablesOf(final String template) {
    final var names = new ArrayList<String>();
    final var matcher = VARIABLE.matcher(template);

    while (matcher.find()) {
      names.add(matcher.group(1));
    }

    return Collections.unmodifiableList(names);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(final Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(final Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }
}
