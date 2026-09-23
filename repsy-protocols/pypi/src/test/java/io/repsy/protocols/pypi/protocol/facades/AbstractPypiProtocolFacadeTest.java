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
package io.repsy.protocols.pypi.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.pypi.shared.python_package.services.PypiPackageService;
import io.repsy.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractPypiProtocolFacade")
class AbstractPypiProtocolFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "pypi";

  @Mock private PypiStorageService<UUID> storageService;
  @Mock private PypiPackageService<UUID> packageService;
  @Mock private BaseUsages usages;

  private AbstractPypiProtocolFacade<UUID> facade;
  private BaseRepoInfo<UUID> repoInfo;

  @BeforeEach
  void setUp() {
    facade = new TestFacade(storageService, packageService);
    repoInfo =
        BaseRepoInfo.<UUID>builder()
            .id(REPO_ID)
            .storageKey(REPO_ID)
            .name(REPO_NAME)
            .allowOverride(true)
            .type(RepoType.PYPI)
            .build();
  }

  static class TestFacade extends AbstractPypiProtocolFacade<UUID> {

    TestFacade(
        final PypiStorageService<UUID> storageService,
        final PypiPackageService<UUID> packageService) {
      super(storageService, packageService);
    }
  }

  private ProtocolContext context() {
    final var ctx = new ProtocolContext();
    ctx.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath(""))
            .repoInfo(repoInfo)
            .build());
    return ctx;
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static MockMultipartFile file(final String filename, final byte[] bytes) {
    return new MockMultipartFile("content", filename, "application/octet-stream", bytes);
  }

  private static Map<String, Object> form(
      final String name, final String version, final String sha256Digest) {
    final var params = new HashMap<String, Object>();
    params.put("name", name);
    params.put("version", version);
    if (sha256Digest != null) {
      params.put("sha256_digest", sha256Digest);
    }
    return params;
  }

  // =========================================================================

  @Nested
  @DisplayName("uploadPackage() sha256 digest validation (RPS-1224/RPS-1225)")
  class DigestValidationTests {

    @Test
    @DisplayName("rejects an upload with no sha256_digest field before any storage write")
    void rejectsMissingDigest() {
      final var bytes = "content".getBytes();
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var params = form("pkg", "1.0.0", null);

      assertThatThrownBy(() -> facade.uploadPackage(context(), params, file))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("sha256DigestMissing");

      verifyNoInteractions(storageService, packageService);
    }

    @Test
    @DisplayName("rejects an upload whose digest does not match the actual bytes, writing nothing")
    void rejectsMismatchedDigest() {
      final var bytes = "content".getBytes();
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var params = form("pkg", "1.0.0", "deadbeef");

      assertThatThrownBy(() -> facade.uploadPackage(context(), params, file))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("sha256DigestMismatch");

      verifyNoInteractions(storageService, packageService);
    }

    @Test
    @DisplayName("accepts a matching digest and normalizes an uppercase one to lowercase")
    void acceptsMatchingDigestAndNormalizesCase() throws Exception {
      final var bytes = "content".getBytes();
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var upperCaseDigest = sha256Hex(bytes).toUpperCase(java.util.Locale.ROOT);
      final var params = form("pkg", "1.0.0", upperCaseDigest);

      when(storageService.writePackageArchive(any(), any(), any(), any())).thenReturn(usages);

      facade.uploadPackage(context(), params, file);

      final var captor = ArgumentCaptor.forClass(PackageUploadForm.class);
      verify(storageService)
          .writePackageArchive(eq(REPO_ID), eq(REPO_NAME), captor.capture(), eq(file));
      assertThat(captor.getValue().getSha256_digest()).isEqualTo(sha256Hex(bytes));
    }

    @Test
    @DisplayName(
        "stores the server-computed digest, not the client's, even when it already matched")
    void storesServerComputedDigest() throws Exception {
      final var bytes = "content".getBytes();
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var digest = sha256Hex(bytes);
      final var params = form("pkg", "1.0.0", digest);

      when(storageService.writePackageArchive(any(), any(), any(), any())).thenReturn(usages);

      facade.uploadPackage(context(), params, file);

      final var captor = ArgumentCaptor.forClass(PackageUploadForm.class);
      verify(storageService).writePackageArchive(any(), any(), captor.capture(), any());
      assertThat(captor.getValue().getSha256_digest()).isEqualTo(digest);
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("uploadPackage() override check (RPS-1223)")
  class OverrideCheckTests {

    @Test
    @DisplayName(
        "checks existence keyed only on repoId/normalizedName/filename -- a form version that"
            + " does not match the filename's own version no longer bypasses the check")
    void checksExistenceByFilenameOnlyRegardlessOfFormVersion() throws Exception {
      repoInfo.setAllowOverride(false);
      final var bytes = "content".getBytes();
      final var digest = sha256Hex(bytes);
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      // Form version deliberately does NOT match the filename's own version ("1.0.0").
      final var params = form("pkg", "1.0.0.post9", digest);

      when(storageService.isPackageFileExist(REPO_ID, "pkg", "pkg-1.0.0.tar.gz")).thenReturn(true);

      assertThatThrownBy(() -> facade.uploadPackage(context(), params, file))
          .isInstanceOf(AccessNotAllowedException.class)
          .hasMessage("fileAlreadyExists");

      verify(storageService, never()).writePackageArchive(any(), any(), any(), any());
    }

    @Test
    @DisplayName("allows the upload through when isPackageFileExist reports the path is free")
    void allowsUploadWhenPathIsFree() throws Exception {
      repoInfo.setAllowOverride(false);
      final var bytes = "content".getBytes();
      final var digest = sha256Hex(bytes);
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var params = form("pkg", "1.0.0", digest);

      when(storageService.isPackageFileExist(any(), any(), any())).thenReturn(false);
      when(storageService.writePackageArchive(any(), any(), any(), any())).thenReturn(usages);

      facade.uploadPackage(context(), params, file);

      verify(storageService).writePackageArchive(any(), any(), any(), any());
    }

    @Test
    @DisplayName("skips the existence check entirely when the repo allows override")
    void skipsCheckWhenOverrideAllowed() throws Exception {
      repoInfo.setAllowOverride(true);
      final var bytes = "content".getBytes();
      final var digest = sha256Hex(bytes);
      final var file = file("pkg-1.0.0.tar.gz", bytes);
      final var params = form("pkg", "1.0.0", digest);

      when(storageService.writePackageArchive(any(), any(), any(), any())).thenReturn(usages);

      facade.uploadPackage(context(), params, file);

      verify(storageService, never()).isPackageFileExist(any(), any(), any());
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("uploadPackage() success path")
  class SuccessPathTests {

    @Test
    @DisplayName("normalizes the package name, writes the archive and registers the release")
    void writesArchiveAndRegistersRelease() throws Exception {
      final var bytes = "content".getBytes();
      final var digest = sha256Hex(bytes);
      final var file = file("my_package-1.0.0-py3-none-any.whl", bytes);
      final var params = form("My.Package", "1.0.0", digest);

      when(storageService.writePackageArchive(any(), any(), any(), any())).thenReturn(usages);

      final var ctx = context();
      facade.uploadPackage(ctx, params, file);

      verify(packageService).addOrUpdateRelease(eq(repoInfo), any(PackageUploadForm.class));
      assertThat(ctx.<String>getProperty("artifactName")).isEqualTo("my-package");
      assertThat(ctx.<String>getProperty("artifactVersion")).isEqualTo("1.0.0");
      assertThat(ctx.<BaseUsages>getProperty("usages")).isSameAs(usages);
    }
  }
}
