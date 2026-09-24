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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * RPS-1269: every paged panel list speaks the same query language, {@code page}, {@code size} and
 * {@code sort} as flat parameters, plus {@code q} for the free-text filter, and answers a bad one
 * the same way.
 *
 * <p>One table row per paged operation of the spec. {@link #tableCoversEveryPagedOperation()} fails
 * when the spec gains a paged operation that has no row, so a new list cannot skip the contract.
 * The behaviour checks run against an empty repository of the right type: they pin how the request
 * is read (what is rejected, what is accepted), not what the lists contain. The contents of each
 * list, the {@code q} filter included, are pinned by the integration test of its protocol.
 */
@DisplayName("Paged panel lists: page, size, sort and q")
class PagedListsIT extends AbstractIntegrationTest {

  private static final String SPEC_RESOURCE = "openapi/openapi-spec.yaml";

  /** The names the free-text filters had before they became {@code q}. */
  private static final List<String> OLD_FILTER_NAMES =
      List.of("name", "query", "search", "version", "groupName", "artifactName");

  private static final UUID SCAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /**
   * One paged operation.
   *
   * @param operationId The {@code operationId} in the spec
   * @param type The repository type the path needs, null when the path names no repository
   * @param path The path, with {@code {repo}} standing for the repository name
   * @param hasQ Whether the operation has the free-text filter {@code q}
   * @param sorts The sort properties the operation accepts
   */
  private record Endpoint(
      String operationId, RepoType type, String path, boolean hasQ, List<String> sorts) {

    @Override
    public String toString() {
      return this.operationId;
    }
  }

  private static Endpoint list(
      final String operationId,
      final RepoType type,
      final String path,
      final boolean hasQ,
      final String... sorts) {
    return new Endpoint(operationId, type, path, hasQ, List.of(sorts));
  }

  private static final List<Endpoint> ENDPOINTS =
      List.of(
          list("listUsers", null, "/api/users", true, "createdAt", "username"),
          list("listRepos", null, "/api/repos", true, "createdAt", "name", "type", "diskUsage"),
          list(
              "listDeployTokens",
              RepoType.MAVEN,
              "/api/repos/{repo}/deploy-tokens",
              false,
              "id",
              "name",
              "username",
              "description",
              "readOnly",
              "expirationDate",
              "createdAt"),
          list(
              "listMavenKeyStores",
              RepoType.MAVEN,
              "/api/mvn/key-stores/{repo}",
              false,
              "id",
              "host",
              "displayName"),
          list(
              "listMavenPgpPublicKeys",
              RepoType.MAVEN,
              "/api/mvn/key-stores/{repo}/public-keys",
              false,
              "id",
              "keyId",
              "fingerprint",
              "userId",
              "createdAt"),
          list(
              "listPypiPackages",
              RepoType.PYPI,
              "/api/pypi/packages/{repo}",
              true,
              "id",
              "name",
              "latestVersion",
              "updatedAt"),
          list(
              "listReleases",
              RepoType.PYPI,
              "/api/pypi/packages/{repo}/alpha/releases",
              true,
              "id",
              "version",
              "createdAt"),
          list(
              "listNpmPackages",
              RepoType.NPM,
              "/api/npm/packages/{repo}",
              false,
              "id",
              "name",
              "scope",
              "latestVersion",
              "updatedAt"),
          list(
              "listUnscopedNpmPackages",
              RepoType.NPM,
              "/api/npm/scopes/{repo}/packages",
              true,
              "id",
              "name",
              "scope",
              "latestVersion",
              "updatedAt"),
          list(
              "listNpmPackagesByScope",
              RepoType.NPM,
              "/api/npm/scopes/{repo}/tools/packages",
              true,
              "id",
              "name",
              "scope",
              "latestVersion",
              "updatedAt"),
          list(
              "listNpmPackageVersions",
              RepoType.NPM,
              "/api/npm/packages/{repo}/package/alpha/versions",
              true,
              "id",
              "version",
              "createdAt"),
          list(
              "listNpmScopedPackageVersions",
              RepoType.NPM,
              "/api/npm/packages/{repo}/tools/package/alpha/versions",
              true,
              "id",
              "version",
              "createdAt"),
          list(
              "searchNugetPackages",
              RepoType.NUGET,
              "/api/nuget/packages/{repo}",
              true,
              "packageId"),
          list(
              "listNugetVersions",
              RepoType.NUGET,
              "/api/nuget/packages/{repo}/Alpha.Package/versions",
              true,
              "version",
              "publishedAt"),
          list(
              "listContainsGroupName",
              RepoType.MAVEN,
              "/api/mvn/artifacts/{repo}",
              true,
              "id",
              "groupName",
              "artifactName",
              "lastUpdatedAt"),
          list(
              "listContainsArtifactName",
              RepoType.MAVEN,
              "/api/mvn/artifacts/{repo}/com.example",
              true,
              "id",
              "groupName",
              "artifactName",
              "lastUpdatedAt"),
          list(
              "listMavenArtifactVersions",
              RepoType.MAVEN,
              "/api/mvn/artifacts/{repo}/com.example/lib/versions",
              true,
              "id",
              "versionName",
              "lastUpdatedAt"),
          list(
              "listGolangModules",
              RepoType.GOLANG,
              "/api/go/modules/{repo}",
              false,
              "id",
              "modulePath",
              "createdAt"),
          list(
              "listGolangModuleVersions",
              RepoType.GOLANG,
              "/api/go/modules/{repo}/versions?modulePath=example.com/alpha",
              true,
              "id",
              "version",
              "createdAt"),
          list(
              "searchGolangModules",
              RepoType.GOLANG,
              "/api/go/modules/{repo}/search",
              true,
              "id",
              "modulePath",
              "createdAt"),
          list(
              "listDockerImages",
              RepoType.DOCKER,
              "/api/docker/images/{repo}",
              true,
              "id",
              "name",
              "updatedAt",
              "lastUpdatedAt"),
          list(
              "listDockerImageTags",
              RepoType.DOCKER,
              "/api/docker/images/{repo}/alpha/tags",
              true,
              "id",
              "name",
              "createdAt"),
          list(
              "listTagManifests",
              RepoType.DOCKER,
              "/api/docker/images/{repo}/alpha/tags/latest/manifests",
              true,
              "id",
              "name",
              "createdAt"),
          list(
              "searchCargoCrates",
              RepoType.CARGO,
              "/api/cargo/crates/{repo}",
              true,
              "id",
              "name",
              "maxVersion",
              "downloads",
              "totalDownloads",
              "lastUpdatedAt"),
          list(
              "listCargoCrateVersions",
              RepoType.CARGO,
              "/api/cargo/crates/{repo}/alpha/versions",
              true,
              "version",
              "createdAt"),
          list("listGems", RepoType.RUBY, "/api/ruby/gems/{repo}", true, "id", "name", "updatedAt"),
          list(
              "listGemVersions",
              RepoType.RUBY,
              "/api/ruby/gems/{repo}/alpha/versions",
              true,
              "id",
              "version",
              "createdAt"),
          list(
              "searchHelmCharts",
              RepoType.HELM,
              "/api/helm/charts/{repo}",
              true,
              "name",
              "latestVersion",
              "updatedAt",
              "createdAt",
              "lastUpdatedAt"),
          list(
              "listVulnerabilityScans",
              RepoType.MAVEN,
              "/api/repos/{repo}/artifacts/alpha/versions/1.0.0/scans",
              false,
              "createdAt"),
          list(
              "getVulnerabilityScanFindings",
              RepoType.MAVEN,
              "/api/repos/{repo}/scans/" + SCAN_ID + "/findings",
              false,
              "id",
              "cveId",
              "severity",
              "packageName",
              "packageVersion",
              "fixedVersion",
              "description",
              "referenceUrl",
              "fixStatus",
              "cvssScore",
              "cvssVector"),
          list("listSecurityScans", null, "/api/security/scans", false, "createdAt"));

  static Stream<Endpoint> endpoints() {
    return ENDPOINTS.stream();
  }

  static Stream<Arguments> invalidPagingOnEveryEndpoint() {
    return ENDPOINTS.stream()
        .flatMap(
            endpoint ->
                PagingAssertions.invalidPagingParams()
                    .map(args -> Arguments.of(endpoint, args.get()[0], args.get()[1])));
  }

  static Stream<Arguments> filterEndpointsAndOldNames() {
    return ENDPOINTS.stream()
        .flatMap(
            endpoint -> OLD_FILTER_NAMES.stream().map(oldName -> Arguments.of(endpoint, oldName)));
  }

  // ---------------------------------------------------------------------------------------------
  // The table against the spec
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the table has a row for every paged operation of the spec, and only those")
  void tableCoversEveryPagedOperation() throws IOException {
    final var paged = new TreeSet<String>();
    final var withQ = new TreeSet<String>();

    for (final var operation : pagedOperations().entrySet()) {
      paged.add(operation.getKey());

      if (queryParameterNames(operation.getValue()).contains("q")) {
        withQ.add(operation.getKey());
      }
    }

    assertThat(ENDPOINTS.stream().map(Endpoint::operationId).toList())
        .as("rows of the table")
        .containsExactlyInAnyOrderElementsOf(paged);
    assertThat(
            ENDPOINTS.stream()
                .filter(Endpoint::hasQ)
                .map(Endpoint::operationId)
                .collect(Collectors.toSet()))
        .as("operations of the table that filter by q")
        .isEqualTo(withQ);
  }

  @Test
  @DisplayName("no operation of the spec still has a pageable parameter or a Pageable schema")
  void noPageableObjectIsLeft() throws IOException {
    final var doc = loadSpec();
    final var schemas = asMap(asMap(doc.get("components")).get("schemas"));

    assertThat(schemas).doesNotContainKey("Pageable");

    final var findings = new TreeSet<String>();

    for (final var operation : operations(doc).entrySet()) {
      for (final var parameter : parameters(operation.getValue())) {
        final var name = String.valueOf(asMap(parameter).get("name"));

        if ("pageable".equals(name)) {
          findings.add(operation.getKey());
        }
      }
    }

    assertThat(findings).as("operations with a pageable parameter").isEmpty();
  }

  @Test
  @DisplayName("a paged operation takes the shared Page, Size and Sort last, and q just before")
  void pagedOperationsUseTheSharedParameters() throws IOException {
    final var findings = new TreeSet<String>();

    for (final var operation : pagedOperations().entrySet()) {
      final var parameters = parameters(operation.getValue());
      final var refs = parameters.stream().map(parameter -> asMap(parameter).get("$ref")).toList();
      final var tail = refs.subList(Math.max(0, refs.size() - 3), refs.size());

      if (!tail.equals(
          List.of(
              "#/components/parameters/Page",
              "#/components/parameters/Size",
              "#/components/parameters/Sort"))) {
        findings.add(operation.getKey() + ": the last three parameters are " + tail);
      }

      final var named = queryParameterNames(operation.getValue());

      for (final var own : List.of("page", "size", "sort")) {
        if (named.contains(own)) {
          findings.add(operation.getKey() + ": declares " + own + " itself");
        }
      }

      if (named.contains("q")) {
        final var index = named.indexOf("q");

        if (index != named.size() - 1) {
          findings.add(operation.getKey() + ": q is not the last filter " + named);
        }
      }
    }

    assertThat(findings).isEmpty();
  }

  @Test
  @DisplayName("the shared paging parameters document the defaults and the bounds")
  void sharedParametersDocumentDefaultsAndBounds() throws IOException {
    final var shared = asMap(asMap(loadSpec().get("components")).get("parameters"));
    final var page = asMap(asMap(shared.get("Page")).get("schema"));
    final var size = asMap(asMap(shared.get("Size")).get("schema"));
    final var sort = asMap(shared.get("Sort"));

    assertThat(asMap(shared.get("Page")))
        .containsEntry("name", "page")
        .containsEntry("in", "query");
    assertThat(page).containsEntry("default", 0).containsEntry("minimum", 0);
    assertThat(asMap(shared.get("Size"))).containsEntry("name", "size");
    assertThat(size)
        .containsEntry("default", 10)
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 100);
    assertThat(sort).containsEntry("name", "sort").containsEntry("explode", true);
    assertThat(asMap(sort.get("schema"))).containsEntry("type", "array");
  }

  @Test
  @DisplayName("no paged operation documents a free-text filter under its old name")
  void oldFilterNamesAreGoneFromTheSpec() throws IOException {
    final var findings = new TreeSet<String>();

    for (final var operation : pagedOperations().entrySet()) {
      for (final var name : queryParameterNames(operation.getValue())) {
        if (OLD_FILTER_NAMES.contains(name)) {
          findings.add(operation.getKey() + " still documents " + name);
        }
      }
    }

    assertThat(findings).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // The behaviour
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  @DisplayName("answers the first page of ten by default, and every parameter it documents")
  void defaultsAndDocumentedParameters(final Endpoint endpoint) throws Exception {
    final var token = this.adminBearerToken();
    final var path = this.pathOf(endpoint);

    final var plain = this.request(path, token);
    assertThat(plain.getResponse().getStatus()).as("status of %s", path).isIn(200, 404);

    if (plain.getResponse().getStatus() == 200) {
      final var body = plain.getResponse().getContentAsString();

      assertThat(JsonPath.<Integer>read(body, "$.data.page.size")).isEqualTo(10);
      assertThat(JsonPath.<Integer>read(body, "$.data.page.number")).isZero();
    }

    assertThat(this.request(path, token, "page", "0", "size", "100").getResponse().getStatus())
        .as("size 100 of %s", path)
        .isIn(200, 404);

    if (endpoint.hasQ()) {
      assertThat(this.request(path, token, "q", "zzz-no-such-thing").getResponse().getStatus())
          .as("q of %s", path)
          .isIn(200, 404);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  @DisplayName("accepts every documented sort property, in both directions, and several at once")
  void acceptsEveryDocumentedSort(final Endpoint endpoint) throws Exception {
    final var token = this.adminBearerToken();
    final var path = this.pathOf(endpoint);

    for (final var property : endpoint.sorts()) {
      for (final var direction : List.of("asc", "desc")) {
        final var status =
            this.request(path, token, "sort", property + "," + direction).getResponse().getStatus();

        assertThat(status).as("sort=%s,%s on %s", property, direction, path).isIn(200, 404);
      }
    }

    if (endpoint.sorts().size() > 1) {
      final var status =
          this.request(
                  path,
                  token,
                  "sort",
                  endpoint.sorts().get(0) + ",asc",
                  "sort",
                  endpoint.sorts().get(1) + ",desc")
              .getResponse()
              .getStatus();

      assertThat(status).as("two sort keys on %s", path).isIn(200, 404);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  @DisplayName("answers 400 validationError naming sort for a property it cannot sort by")
  void rejectsAnUnknownSort(final Endpoint endpoint) throws Exception {
    final var token = this.adminBearerToken();

    PagingAssertions.expectInvalidParameter(
        this.perform(
            this.builder(this.pathOf(endpoint), token, "sort", PagingAssertions.UNKNOWN_SORT)),
        "sort");
  }

  @ParameterizedTest(name = "{0} {1}={2}")
  @MethodSource("invalidPagingOnEveryEndpoint")
  @DisplayName("answers 400 validationError naming the parameter for a bad page or size")
  void rejectsBadPaging(final Endpoint endpoint, final String param, final String value)
      throws Exception {
    final var token = this.adminBearerToken();

    PagingAssertions.expectInvalidParameter(
        this.perform(this.builder(this.pathOf(endpoint), token, param, value)), param);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("filterEndpointsAndOldNames")
  @DisplayName("ignores the old name of the filter: it is an unknown parameter now")
  void ignoresTheOldFilterName(final Endpoint endpoint, final String oldName) throws Exception {
    final var token = this.adminBearerToken();
    final var path = this.pathOf(endpoint);

    final var plain = this.request(path, token).getResponse();
    final var old = this.request(path, token, oldName, "zzz-no-such-thing").getResponse();

    assertThat(old.getStatus())
        .as("%s with %s= answers like the plain list", path, oldName)
        .isEqualTo(plain.getStatus());

    if (plain.getStatus() == 200) {
      assertThat(old.getContentAsString())
          .as("%s with %s= lists the same rows as the plain list", path, oldName)
          .isEqualTo(plain.getContentAsString());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** The path of the endpoint with a repository of its type created for it, when it needs one. */
  private String pathOf(final Endpoint endpoint) {
    if (endpoint.type() == null) {
      return endpoint.path();
    }

    final var repo = this.seedRepo(endpoint.type(), uniqueRepoName("paged"));

    return endpoint.path().replace("{repo}", repo.getName());
  }

  /** A GET of {@code path} with the query parameters given as name, value, name, value... */
  private MockHttpServletRequestBuilder builder(
      final String path, final String token, final String... nameValuePairs) {

    final var request = get(path).header(AUTHORIZATION, token);

    for (int i = 0; i < nameValuePairs.length; i += 2) {
      request.param(nameValuePairs[i], nameValuePairs[i + 1]);
    }

    return request;
  }

  private MvcResult request(final String path, final String token, final String... nameValuePairs)
      throws Exception {
    return this.perform(this.builder(path, token, nameValuePairs)).andReturn();
  }

  private static Map<String, Map<String, Object>> operations(final Map<String, Object> doc) {
    final var operations = new TreeMap<String, Map<String, Object>>();

    asMap(doc.get("paths"))
        .forEach(
            (template, item) ->
                asMap(item)
                    .forEach(
                        (method, operation) -> {
                          if ("get".equals(method)) {
                            final var merged = new LinkedHashMap<>(asMap(operation));
                            merged.put("$path", template);
                            merged.put("$pathParameters", asMap(item).get("parameters"));
                            operations.put(String.valueOf(merged.get("operationId")), merged);
                          }
                        }));

    return operations;
  }

  /** The operations that take the shared {@code Page} parameter, by operationId. */
  private static Map<String, Map<String, Object>> pagedOperations() throws IOException {
    final var paged = new TreeMap<String, Map<String, Object>>();

    operations(loadSpec())
        .forEach(
            (id, operation) -> {
              final var refs =
                  parameters(operation).stream()
                      .map(parameter -> asMap(parameter).get("$ref"))
                      .collect(Collectors.toSet());

              if (refs.contains("#/components/parameters/Page")) {
                paged.put(id, operation);
              }
            });

    return paged;
  }

  /** The parameters of the operation itself, references left as they are. */
  private static List<Object> parameters(final Map<String, Object> operation) {
    final var parameters = operation.get("parameters");

    return parameters == null ? List.of() : asList(parameters);
  }

  /** The names of the operation's own query parameters, in order. */
  private static List<String> queryParameterNames(final Map<String, Object> operation) {
    return parameters(operation).stream()
        .map(parameter -> asMap(parameter))
        .filter(parameter -> "query".equals(parameter.get("in")))
        .map(parameter -> String.valueOf(parameter.get("name")))
        .toList();
  }

  private static Map<String, Object> loadSpec() throws IOException {
    final var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);

    try (var in = new ClassPathResource(SPEC_RESOURCE).getInputStream()) {
      return asMap(new Yaml(new SafeConstructor(options)).load(in));
    }
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
