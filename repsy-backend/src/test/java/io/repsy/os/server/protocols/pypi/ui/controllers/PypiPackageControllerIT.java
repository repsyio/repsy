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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration tests for {@code /api/pypi/packages/*}. */
@Testcontainers
@Transactional
@AutoConfigureMockMvc
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("PypiPackageController /api/pypi/packages/*")
class PypiPackageControllerIT {

  private static final int API_PORT = 8080;
  private static final String VALID_PASSWORD = "Password1!";
  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", PypiPackageControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-pypi-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @PersistenceContext private EntityManager entityManager;

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
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(unique("pypi"), role, hash, salt);
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userTxService.getUserById(userInfo.getId());
  }

  private String bearerToken(final UserInfo user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
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
    final var content = (packageName + "-" + version + "\n").getBytes();
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
        .containsEntry("summary", "Integration test package 2.0.0");

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
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertErrorEnvelope(malformedResponse, "accessNotAllowed", "accessNotAllowed");
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
}
