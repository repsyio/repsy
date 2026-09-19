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
package io.repsy.os.server.protocols.pypi.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Full-stack integration tests for {@code /api/pypi/packages/*}. */
@DisplayName("PypiPackageController /api/pypi/packages/*")
class PypiPackageControllerIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static RequestPostProcessor port(final int port) {
    return request -> {
      request.setLocalPort(port);
      return request;
    };
  }

  private static MockHttpServletRequestBuilder api(final MockHttpServletRequestBuilder request) {
    return request.with(port(API_PORT));
  }

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private UserInfo createUser(final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordHasher.hash(VALID_PASSWORD);
    final var userInfo = this.userTxService.create(unique("pypi"), role, hash, salt);
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userTxService.getUserById(userInfo.getId());
  }

  private String bearerToken(final UserInfo user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private RepoInfo createRepo(final boolean privateRepo) {
    final var repoInfo =
        this.repoTxService.createRepo(unique("pypi"), RepoType.PYPI, privateRepo, "PyPI IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());
    return repoInfo;
  }

  private void upload(
      final RepoInfo repo, final String packageName, final String version, final String filename)
      throws Exception {
    assertThat(this.repoTxService.getRepoByNameAndType(repo.getName(), RepoType.PYPI)).isPresent();
    final var content = archiveContent(filename, packageName + "-" + version + "\n");
    final var sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(content);
    final var digest = java.util.HexFormat.of().formatHex(sha256);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", version);
    parameters.put("filetype", filename.endsWith(".whl") ? "bdist_wheel" : "sdist");
    parameters.put("pyversion", "source");
    parameters.put("metadata_version", "2.1");
    parameters.put("summary", "Integration test package " + version);
    parameters.put("author", "Repsy");
    parameters.put("author_email", "test@repsy.io");
    parameters.put("license", "Apache-2.0");
    parameters.put("home_page", "https://example.test/pypi");
    parameters.put("description", "A complete PyPI integration-test fixture");
    parameters.put("classifiers", new String[] {"Programming Language :: Python :: 3"});
    parameters.put("project_urls", new String[] {"Homepage,https://example.test/pypi"});
    parameters.put("requires_dist", new String[] {"requests>=2"});
    parameters.put("requires_python", ">=3.11");
    parameters.put("description_content_type", "text/plain");
    parameters.put("sha256_digest", digest);

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
  }

  private static byte[] archiveContent(final String filename, final String entryContent)
      throws IOException {
    if (filename.endsWith(".whl")) {
      final var output = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(output)) {
        zip.putNextEntry(new ZipEntry("fixture/data.txt"));
        zip.write(entryContent.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return output.toByteArray();
    }

    final var output = new ByteArrayOutputStream();
    final var bytes = entryContent.getBytes(StandardCharsets.UTF_8);
    try (var gzip = new GZIPOutputStream(output);
        var tar = new TarArchiveOutputStream(gzip)) {
      final var entry = new TarArchiveEntry("fixture/data.txt");
      entry.setSize(bytes.length);
      tar.putArchiveEntry(entry);
      tar.write(bytes);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return output.toByteArray();
  }

  private static String body(final ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private static void assertSuccessEnvelope(final String response, final String msgId) {
    final Map<String, Object> envelope = JsonPath.read(response, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null);
    assertThat((String) envelope.get("text")).isNotBlank();
  }

  private static void assertErrorEnvelope(
      final String response, final String msgId, final String data) {
    final Map<String, Object> envelope = JsonPath.read(response, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "ERROR")
        .containsEntry("data", data);
    assertThat((String) envelope.get("errorCode")).matches(UUID_PATTERN);
  }

  @Test
  @DisplayName("lists packages, filters by name, lists releases and returns complete details")
  void listsAndReturnsCompletePackageData() throws Exception {
    final var repo = this.createRepo(true);
    final var caller = this.createUser(UserRole.USER);
    final var token = this.bearerToken(caller);

    this.upload(repo, "My_Package", "1.0.0", "My_Package-1.0.0.tar.gz");
    this.upload(repo, "my.package", "2.0.0", "my-package-2.0.0-py3-none-any.whl");
    this.upload(repo, "other-package", "0.1.0", "other-package-0.1.0.tar.gz");

    final var packageResponse =
        body(
            this.mockMvc
                .perform(
                    api(get("/api/pypi/packages/" + repo.getName() + "?page=0&size=2"))
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(packageResponse, "packagesFetched");
    final Map<String, Object> packagePage = JsonPath.read(packageResponse, "$.data");
    assertThat(packagePage).containsOnlyKeys("content", "page");
    assertThat((List<Map<String, Object>>) packagePage.get("content")).hasSize(2);
    assertThat((Map<String, Object>) packagePage.get("page"))
        .containsOnlyKeys("size", "number", "totalElements", "totalPages")
        .containsEntry("size", 2)
        .containsEntry("number", 0)
        .containsEntry("totalElements", 2)
        .containsEntry("totalPages", 1);
    assertThat((Map<String, Object>) JsonPath.read(packageResponse, "$.data.content[0]"))
        .containsOnlyKeys("name", "stableVersion", "latestVersion", "updatedAt");

    final var filteredResponse =
        body(
            this.mockMvc
                .perform(
                    api(get(
                            "/api/pypi/packages/"
                                + repo.getName()
                                + "?name=My_Package&page=0&size=20"))
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(filteredResponse, "packagesFetched");
    assertThat((List<Map<String, Object>>) JsonPath.read(filteredResponse, "$.data.content"))
        .extracting(item -> item.get("name"))
        .containsExactly("My_Package");

    final var releasesResponse =
        body(
            this.mockMvc
                .perform(
                    api(get(
                            "/api/pypi/packages/"
                                + repo.getName()
                                + "/my-package/releases?version=2&page=0&size=20"))
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(releasesResponse, "releasesFetched");
    assertThat((Map<String, Object>) JsonPath.read(releasesResponse, "$.data.content[0]"))
        .containsOnlyKeys(
            "version", "createdAt", "finalRelease", "preRelease", "postRelease", "devRelease")
        .containsEntry("version", "2.0.0")
        .containsEntry("finalRelease", true)
        .containsEntry("preRelease", false)
        .containsEntry("postRelease", false)
        .containsEntry("devRelease", false);

    final var detailResponse =
        body(
            this.mockMvc
                .perform(
                    api(get("/api/pypi/packages/" + repo.getName() + "/MY-PACKAGE/releases/2.0.0"))
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(detailResponse, "releaseDetailFetched");
    assertThat((Map<String, Object>) JsonPath.read(detailResponse, "$.data"))
        .containsOnlyKeys(
            "id",
            "packageName",
            "stableVersion",
            "version",
            "finalRelease",
            "preRelease",
            "postRelease",
            "devRelease",
            "requiresPython",
            "summary",
            "homePage",
            "author",
            "authorEmail",
            "license",
            "description",
            "descriptionContentType",
            "createdAt",
            "classifiers",
            "projectUrls")
        .containsEntry("packageName", "My_Package")
        .containsEntry("stableVersion", "2.0.0")
        .containsEntry("version", "2.0.0")
        .containsEntry("requiresPython", ">=3.11")
        .containsEntry("summary", "Integration test package 2.0.0")
        .containsEntry("descriptionContentType", "text/plain");
    assertThat((List<Map<String, Object>>) JsonPath.read(detailResponse, "$.data.classifiers"))
        .containsExactly(Map.of("classifier", "Programming Language", "value", "Python :: 3"));
    assertThat((List<Map<String, Object>>) JsonPath.read(detailResponse, "$.data.projectUrls"))
        .containsExactly(Map.of("label", "Homepage", "url", "https://example.test/pypi"));

    final var latestResponse =
        body(
            this.mockMvc
                .perform(
                    api(get("/api/pypi/packages/" + repo.getName() + "/my_package"))
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(latestResponse, "releaseDetailFetched");
    assertThat((String) JsonPath.read(latestResponse, "$.data.version")).isEqualTo("2.0.0");
  }

  @Test
  @DisplayName("enforces private-repository reads, public anonymous reads and complete errors")
  void enforcesAuthorizationAndErrorEnvelope() throws Exception {
    final var privateRepo = this.createRepo(true);
    final var publicRepo = this.createRepo(false);
    final var caller = this.createUser(UserRole.USER);
    final var token = this.bearerToken(caller);
    this.upload(privateRepo, "auth-package", "1.0.0", "auth-package-1.0.0.tar.gz");
    this.upload(publicRepo, "public-package", "1.0.0", "public-package-1.0.0.tar.gz");

    final var privateResponse =
        this.mockMvc
            .perform(api(get("/api/pypi/packages/" + privateRepo.getName())))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertErrorEnvelope(privateResponse, "unAuthorized", "unAuthorized");

    final var publicResponse =
        this.mockMvc
            .perform(api(get("/api/pypi/packages/" + publicRepo.getName())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertSuccessEnvelope(publicResponse, "packagesFetched");

    final var malformedResponse =
        this.mockMvc
            .perform(
                api(get("/api/pypi/packages/" + privateRepo.getName()))
                    .header(AUTHORIZATION, "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertErrorEnvelope(malformedResponse, "accessNotAllowed", "accessNotAllowed");
  }

  @Test
  @DisplayName("returns complete pagination metadata for empty and out-of-range pages")
  void handlesEmptyAndOutOfRangePages() throws Exception {
    final var repo = this.createRepo(false);

    final var emptyResponse =
        body(
            this.mockMvc
                .perform(api(get("/api/pypi/packages/" + repo.getName() + "?page=0&size=2")))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(emptyResponse, "packagesFetched");
    assertThat((List<?>) JsonPath.read(emptyResponse, "$.data.content")).isEmpty();
    assertThat((Map<String, Object>) JsonPath.read(emptyResponse, "$.data.page"))
        .containsOnlyKeys("size", "number", "totalElements", "totalPages")
        .containsEntry("size", 2)
        .containsEntry("number", 0)
        .containsEntry("totalElements", 0)
        .containsEntry("totalPages", 0);

    this.upload(repo, "page-package", "1.0.0", "page-package-1.0.0.tar.gz");
    final var pageResponse =
        body(
            this.mockMvc
                .perform(api(get("/api/pypi/packages/" + repo.getName() + "?page=1&size=1")))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(pageResponse, "packagesFetched");
    assertThat((List<?>) JsonPath.read(pageResponse, "$.data.content")).isEmpty();
    assertThat((Map<String, Object>) JsonPath.read(pageResponse, "$.data.page"))
        .containsEntry("size", 1)
        .containsEntry("number", 1)
        .containsEntry("totalElements", 1)
        .containsEntry("totalPages", 1);

    final var noMatchResponse =
        body(
            this.mockMvc
                .perform(api(get("/api/pypi/packages/" + repo.getName() + "?name=does-not-exist")))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(noMatchResponse, "packagesFetched");
    assertThat((List<?>) JsonPath.read(noMatchResponse, "$.data.content")).isEmpty();
  }

  @Test
  @DisplayName("returns complete errors for unknown packages, releases and release filters")
  void handlesMissingPackageReleaseAndFilter() throws Exception {
    final var repo = this.createRepo(false);
    this.upload(repo, "known-package", "1.0.0", "known-package-1.0.0.tar.gz");
    final var path = "/api/pypi/packages/" + repo.getName();

    final var unknownPackage =
        body(
            this.mockMvc
                .perform(api(get(path + "/missing-package")))
                .andExpect(status().isNotFound()));
    assertErrorEnvelope(unknownPackage, "packageNotFound", "packageNotFound");

    final var unknownRelease =
        body(
            this.mockMvc
                .perform(api(get(path + "/known-package/releases/9.9.9")))
                .andExpect(status().isNotFound()));
    assertErrorEnvelope(unknownRelease, "releaseNotFound", "releaseNotFound");

    final var noReleaseMatch =
        body(
            this.mockMvc
                .perform(api(get(path + "/known-package/releases?version=9.9")))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(noReleaseMatch, "releasesFetched");
    assertThat((List<?>) JsonPath.read(noReleaseMatch, "$.data.content")).isEmpty();
  }

  @Test
  @DisplayName("deletes a release and package, including normalized names and storage files")
  void deletesReleaseAndPackage() throws Exception {
    final var repo = this.createRepo(true);
    final var admin = this.createUser(UserRole.ADMIN);
    final var token = this.bearerToken(admin);
    this.upload(repo, "Delete_Package", "1.0.0", "delete-package-1.0.0.tar.gz");
    this.upload(repo, "delete.package", "2.0.0", "delete-package-2.0.0-py3-none-any.whl");

    final var releaseResponse =
        this.mockMvc
            .perform(
                api(delete(
                        "/api/pypi/packages/" + repo.getName() + "/DELETE-PACKAGE/releases/1.0.0"))
                    .header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertSuccessEnvelope(releaseResponse, "packageReleaseDeleted");

    final var packageResponse =
        this.mockMvc
            .perform(
                api(delete("/api/pypi/packages/" + repo.getName() + "/delete_package"))
                    .header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertSuccessEnvelope(packageResponse, "packageDeleted");

    final var missingResponse =
        this.mockMvc
            .perform(
                api(delete("/api/pypi/packages/" + repo.getName() + "/delete-package"))
                    .header(AUTHORIZATION, token))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertErrorEnvelope(missingResponse, "packageNotFound", "packageNotFound");
  }

  @Test
  @DisplayName("preserves sibling releases and rejects read-only deletes and unsupported verbs")
  void protectsDeletesAndRouteMappings() throws Exception {
    final var repo = this.createRepo(true);
    final var admin = this.createUser(UserRole.ADMIN);
    final var user = this.createUser(UserRole.USER);
    final var adminToken = this.bearerToken(admin);
    final var userToken = this.bearerToken(user);
    this.upload(repo, "keep-package", "1.0.0", "keep-package-1.0.0.tar.gz");
    this.upload(repo, "keep-package", "2.0.0", "keep-package-2.0.0.whl");

    final var readOnlyResponse =
        body(
            this.mockMvc
                .perform(
                    api(delete("/api/pypi/packages/" + repo.getName() + "/keep-package"))
                        .header(AUTHORIZATION, userToken))
                .andExpect(status().isUnauthorized()));
    assertErrorEnvelope(readOnlyResponse, "unAuthorized", "unAuthorized");

    final var releaseDeleteResponse =
        body(
            this.mockMvc
                .perform(
                    api(delete(
                            "/api/pypi/packages/"
                                + repo.getName()
                                + "/KEEP_PACKAGE/releases/1.0.0"))
                        .header(AUTHORIZATION, adminToken))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(releaseDeleteResponse, "packageReleaseDeleted");

    final var remainingResponse =
        body(
            this.mockMvc
                .perform(
                    api(get("/api/pypi/packages/" + repo.getName() + "/keep.package"))
                        .header(AUTHORIZATION, adminToken))
                .andExpect(status().isOk()));
    assertSuccessEnvelope(remainingResponse, "releaseDetailFetched");
    assertThat((String) JsonPath.read(remainingResponse, "$.data.version")).isEqualTo("2.0.0");

    final var unsupportedResponse =
        body(
            this.mockMvc
                .perform(
                    api(post("/api/pypi/packages/" + repo.getName() + "/keep-package"))
                        .header(AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound()));
    assertThat(JsonPath.<Map<String, Object>>read(unsupportedResponse, "$"))
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", "itemNotFound")
        .containsEntry("type", "ERROR")
        .containsEntry("data", null);
    assertThat((String) JsonPath.read(unsupportedResponse, "$.errorCode")).matches(UUID_PATTERN);
    verify(this.usageUpdateService, atLeastOnce()).updateUsage(any(UsageChangedInfo.class));
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String PACKAGES = "/api/pypi/packages/{repo}";
    private static final String PACKAGES_LIKE = PACKAGES + "?name=alpha";
    private static final String RELEASES = PACKAGES + "/alpha/releases";
    private static final String RELEASES_LIKE = RELEASES + "?version=1";

    static Stream<String> endpoints() {
      return Stream.of(PACKAGES, PACKAGES_LIKE, RELEASES, RELEASES_LIKE);
    }

    static Stream<Arguments> acceptedSorts() {
      final var packageSorts =
          Stream.of(PACKAGES, PACKAGES_LIKE)
              .flatMap(
                  path ->
                      Stream.of("id", "name", "latestVersion", "updatedAt")
                          .map(property -> Arguments.of(path, property)));
      final var releaseSorts =
          Stream.of(RELEASES, RELEASES_LIKE)
              .flatMap(
                  path ->
                      Stream.of("id", "version", "createdAt")
                          .map(property -> Arguments.of(path, property)));

      return Stream.concat(packageSorts, releaseSorts);
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private record Seed(RepoInfo repo, String token) {}

    private Seed seed() throws Exception {
      final var it = PypiPackageControllerIT.this;
      final var repo = it.createRepo(true);
      final var token = it.bearerToken(it.createUser(UserRole.USER));

      it.upload(repo, "alpha", "1.0.0", "alpha-1.0.0.tar.gz");
      it.upload(repo, "alpha", "1.1.0", "alpha-1.1.0.tar.gz");
      it.upload(repo, "beta", "2.0.0", "beta-2.0.0.tar.gz");

      return new Seed(repo, token);
    }

    private ResultActions list(
        final Seed seed, final String path, final String param, final String value)
        throws Exception {
      return PypiPackageControllerIT.this.mockMvc.perform(
          api(get(path, seed.repo().getName()).param(param, value))
              .header(AUTHORIZATION, seed.token()));
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var seed = this.seed();

      this.list(seed, path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(seed, path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the packages and releases by the requested sort property")
    void ordersByRequestedProperty() throws Exception {
      final var seed = this.seed();

      this.list(seed, PACKAGES, "sort", "name,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("alpha"));
      this.list(seed, PACKAGES, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("beta"));
      this.list(seed, RELEASES, "sort", "version,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.0.0"));
      this.list(seed, RELEASES, "sort", "version,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.1.0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seed(), path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(this.list(this.seed(), path, param, value), param);
    }
  }
}
