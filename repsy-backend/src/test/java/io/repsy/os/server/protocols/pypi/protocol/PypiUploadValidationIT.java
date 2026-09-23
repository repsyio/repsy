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
package io.repsy.os.server.protocols.pypi.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.generated.model.ReleaseProjectURLInfo;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1224: a missing {@code sha256_digest} form field is a {@code 400 sha256DigestMissing}, not an
 * unhandled 500, and the check runs before anything is written to storage (no orphan).
 *
 * <p>RPS-1137: an over-long package name, version or {@code requires_python} is rejected the same
 * way, before anything is written; an over-long descriptive field, classifier or Project-URL is
 * dropped instead and the rest of the publish goes through, matching {@code PypiPublishLimits}.
 */
@DisplayName("PyPI upload validation (RPS-1224, RPS-1137)")
class PypiUploadValidationIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private RepoInfo createRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(unique("pypi"), RepoType.PYPI, true, "PyPI IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());
    return repoInfo;
  }

  private static ProtocolContext context(final RepoInfo repo) {
    final var ctx = new ProtocolContext();
    ctx.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    return ctx;
  }

  @Test
  @DisplayName("a missing sha256_digest is a 400 sha256DigestMissing, not a 500")
  void missingSha256DigestIsA400NotA500() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-1.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "1.0.0");
    parameters.put("requires_python", ">=3.9");
    // No sha256_digest field at all.

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sha256DigestMissing");
  }

  @Test
  @DisplayName("the rejected upload leaves nothing downloadable -- the check runs before the write")
  void missingSha256DigestLeavesNothingInStorage() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-2.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "2.0.0");
    parameters.put("requires_python", ">=3.9");

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class);

    assertThatThrownBy(
            () ->
                this.pypiProtocolFacade.downloadArchiveFile(context(repo), "my-package", filename))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }

  private static String repeat(final char c, final int length) {
    return String.valueOf(c).repeat(length);
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  @DisplayName("an over-long package name is a 400 pypiPackageNameTooLong, not a 500")
  void rejectsOverLongPackageName() {
    final var repo = this.createRepo();
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", repeat('n', 256));
    parameters.put("version", "1.0.0");
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", "0".repeat(64));

    final var file =
        new MockMultipartFile(
            "content", "irrelevant-1.0.0.whl", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pypiPackageNameTooLong");
  }

  @Test
  @DisplayName("an over-long version is a 400 pypiVersionTooLong, not a 500")
  void rejectsOverLongVersion() {
    final var repo = this.createRepo();
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", repeat('1', 256));
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", "0".repeat(64));

    final var file =
        new MockMultipartFile(
            "content", "irrelevant-1.0.0.whl", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pypiVersionTooLong");
  }

  @Test
  @DisplayName("an over-long requires_python is a 400 pypiRequiresPythonTooLong, not a 500")
  void rejectsOverLongRequiresPython() {
    final var repo = this.createRepo();
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "1.0.0");
    parameters.put("requires_python", repeat('p', 256));
    parameters.put("sha256_digest", "0".repeat(64));

    final var file =
        new MockMultipartFile(
            "content", "irrelevant-1.0.0.whl", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pypiRequiresPythonTooLong");
  }

  @Test
  @DisplayName("a rejected over-long upload leaves nothing downloadable -- the check runs first")
  void rejectedOverLongUploadLeavesNothingInStorage() {
    final var repo = this.createRepo();
    final var filename = "my_package-3.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", repeat('n', 256));
    parameters.put("version", "3.0.0");
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", "0".repeat(64));

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class);

    assertThatThrownBy(
            () ->
                this.pypiProtocolFacade.downloadArchiveFile(context(repo), "my-package", filename))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }

  @Test
  @DisplayName(
      "drops over-long descriptive fields, a classifier and a Project-URL, and keeps the rest of"
          + " the publish")
  void dropsOverLongDescriptiveFieldsAndEntries() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-4.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "4.0.0");
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", sha256Hex(content));
    parameters.put("home_page", repeat('h', 256));
    parameters.put("author", repeat('a', 256));
    parameters.put("author_email", "short@example.test");
    parameters.put("license", repeat('l', 256));
    parameters.put("description_content_type", repeat('d', 256));
    parameters.put("summary", repeat('s', 5000));
    parameters.put("description", repeat('e', 5000));
    parameters.put(
        "classifiers",
        new String[] {
          "Programming Language :: Python :: 3",
          "Topic :: " + repeat('x', 256),
          "License :: OSI Approved :: MIT License"
        });
    parameters.put(
        "project_urls",
        new String[] {
          "Homepage,https://example.test", repeat('u', 33) + ",https://example.test/other"
        });

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file);
    this.entityManager.flush();

    final var release = this.pypiApiFacade.getReleaseDetail(repo.getId(), "my-package", "4.0.0");

    assertThat(release.getHomePage()).isNull();
    assertThat(release.getAuthor()).isNull();
    assertThat(release.getAuthorEmail()).isEqualTo("short@example.test");
    assertThat(release.getLicense()).isNull();
    assertThat(release.getDescriptionContentType()).isNull();
    assertThat(release.getSummary()).hasSize(5000);
    assertThat(release.getDescription()).hasSize(5000);

    assertThat(release.getClassifiers())
        .extracting(c -> c.getClassifier() + " :: " + c.getValue())
        .containsExactly(
            "Programming Language :: Python :: 3", "License :: OSI Approved :: MIT License");

    assertThat(release.getProjectUrls())
        .extracting(ReleaseProjectURLInfo::getLabel)
        .containsExactly("Homepage");
  }
}
