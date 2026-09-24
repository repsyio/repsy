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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
  private static final String PANEL_PREFIX = "/api/";
  private static final String REPOS_PREFIX = "/api/repos/";
  private static final String BEARER = "bearerAuth";

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
   *   <li>{@code getSupportedRepoTypes}: a constant list, {@code SecurityScanController} takes no
   *       credentials for it.
   *   <li>{@code checkSumdbSupported}: the Go toolchain probes it without credentials and always
   *       gets 404.
   * </ul>
   */
  private static final Set<String> PUBLIC_OPERATIONS =
      Set.of("login", "refreshToken", "getSupportedRepoTypes", "checkSumdbSupported");

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
   *   <li>{@code packageName}, {@code chartName}, {@code crateName}, {@code gemName}, {@code
   *       imageName}, {@code artifactName}, {@code groupName}, {@code tagName}, {@code scope}: the
   *       item of a protocol, named after what that protocol calls it.
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
          "gemName",
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
  @DisplayName("every $ref in the spec resolves")
  void referencesResolve() throws IOException {
    final var doc = loadSpec();
    final var findings = new TreeSet<String>();

    collectBrokenRefs(doc, doc, "#", findings);

    assertNoNewFindings("unresolved references", findings, Map.of());
  }

  @Test
  @DisplayName("ErrorResponse lists the members of the envelope the error handler really writes")
  void errorResponseIsTheRealEnvelope() throws Exception {
    final var doc = loadSpec();
    final var schema =
        asMap(asMap(asMap(doc.get("components")).get("schemas")).get("ErrorResponse"));

    final var body =
        this.perform(MockMvcRequestBuilders.get("/api/usages"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    final Map<String, Object> envelope = JsonPath.read(body, "$");

    assertThat(asMap(schema.get("properties")).keySet())
        .containsExactlyInAnyOrder(ENVELOPE_KEYS)
        .containsExactlyInAnyOrderElementsOf(envelope.keySet());
    assertThat(envelope).containsEntry("type", "ERROR");
    assertThat(asMap(asMap(schema.get("properties")).get("type")).get("$ref"))
        .isEqualTo("#/components/schemas/ResponseType");
  }

  // ---------------------------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------------------------

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
  // Model
  // ---------------------------------------------------------------------------------------------

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
