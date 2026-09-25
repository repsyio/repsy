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
package io.repsy.protocols.golang.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.golang.shared.module.services.GoModuleFilesWriter;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractGoProtocolFacade.upload() storage and row ordering (RPS-1124)")
class AbstractGoProtocolFacadeTest {

  private static final String MODULE = "example.com/mod";
  private static final String VERSION = "v1.0.0";
  private static final long MAX_ZIP_BYTES = 1024 * 1024;

  @Mock private GoStorageService<UUID> storageService;
  @Mock private GoModuleService<UUID> moduleService;

  private final BaseRepoInfo<UUID> repoInfo =
      BaseRepoInfo.<UUID>builder()
          .id(UUID.randomUUID())
          .storageKey(UUID.randomUUID())
          .name("golang")
          .build();

  private AbstractGoProtocolFacade<UUID> facade;

  static class TestFacade extends AbstractGoProtocolFacade<UUID> {

    TestFacade(final GoStorageService<UUID> s, final GoModuleService<UUID> m) {
      super(s, m, MAX_ZIP_BYTES);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade = new TestFacade(this.storageService, this.moduleService);
  }

  private ProtocolContext context(final String modulePath, final String version) {
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(this.repoInfo.getName())
            .relativePath(new RelativePath("/" + modulePath + "/@v/" + version))
            .repoInfo(this.repoInfo)
            .build();
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);

    return ctx;
  }

  private static byte[] moduleZip(final String modulePath, final String version)
      throws IOException {
    final var out = new ByteArrayOutputStream();
    try (final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(modulePath + "@" + version + "/go.mod"));
      zip.write(("module " + modulePath + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    return out.toByteArray();
  }

  private void upload(final String modulePath, final String version) throws IOException {
    final var zip = moduleZip(modulePath, version);
    this.facade.upload(
        this.context(modulePath, version), new ByteArrayInputStream(zip), zip.length);
  }

  /** The real service runs the writer inside its transaction; the mock runs it in place. */
  private void publishRunsFilesWriter() throws IOException {
    when(this.moduleService.publishModule(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.<GoModuleFilesWriter>getArgument(6).write());
  }

  private void assertVersionFilesDeleted(final String relativePath) {
    final var captor = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageService).deleteVersionFiles(captor.capture(), eq("golang"));
    assertThat(captor.getValue().getStorageKey()).isEqualTo(this.repoInfo.getStorageKey());
    assertThat(captor.getValue().getRelativePath().getPath()).isEqualTo(relativePath);
  }

  @Test
  @DisplayName("writes the row first and the three files inside it, and reports their usages")
  void writesRowThenFiles() throws IOException {
    publishRunsFilesWriter();
    when(this.storageService.getModuleZipRelativePath(MODULE, VERSION))
        .thenReturn("/" + MODULE + "/@v/" + VERSION + ".zip");
    when(this.storageService.writeInputStreamToPath(any(), any(InputStream.class), any()))
        .thenReturn(BaseUsages.ofDisk(10));
    final var ctx = this.context(MODULE, VERSION);
    final var zip = moduleZip(MODULE, VERSION);

    this.facade.upload(ctx, new ByteArrayInputStream(zip), zip.length);

    assertThat(ctx.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(30);
    assertThat(ctx.<String>getProperty("artifactName")).isEqualTo(MODULE);
    assertThat(ctx.<String>getProperty("artifactVersion")).isEqualTo(VERSION);
    verify(this.storageService, org.mockito.Mockito.times(3))
        .writeInputStreamToPath(any(), any(InputStream.class), eq("golang"));
    verify(this.storageService, never()).deleteVersionFiles(any(), any());
  }

  @Test
  @DisplayName("does not touch storage when the version row cannot be written")
  void leavesStorageAloneWhenRowFails() throws IOException {
    when(this.moduleService.publishModule(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new ItemAlreadyExistException("goModuleVersionAlreadyExists"));

    assertThatThrownBy(() -> this.upload(MODULE, VERSION))
        .isInstanceOf(ItemAlreadyExistException.class);

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), any());
    verify(this.storageService, never()).deleteVersionFiles(any(), any());
  }

  @Test
  @DisplayName("removes the partly written files of the version when storing fails")
  void discardsPartialFilesWhenStoringFails() throws IOException {
    publishRunsFilesWriter();
    final var failure = new IllegalStateException("disk full");
    when(this.storageService.getModuleZipRelativePath(any(), any()))
        .thenReturn("/" + MODULE + "/@v/" + VERSION + ".zip");
    when(this.storageService.writeInputStreamToPath(any(), any(InputStream.class), any()))
        .thenReturn(BaseUsages.ofDisk(10))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.upload(MODULE, VERSION)).isSameAs(failure);

    this.assertVersionFilesDeleted("/" + MODULE + "/@v/" + VERSION);
  }

  @Test
  @DisplayName("removes the partly written files under the escaped storage path of the module")
  void discardsFilesUnderTheEscapedPath() throws IOException {
    publishRunsFilesWriter();
    final var failure = new IllegalStateException("disk full");
    when(this.storageService.writeInputStreamToPath(any(), any(InputStream.class), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.upload("example.com/Mod", VERSION)).isSameAs(failure);

    this.assertVersionFilesDeleted("/example.com/!mod/@v/" + VERSION);
  }

  @Test
  @DisplayName("stores nothing for a body that is not a module of the URL's path")
  void storesNothingForAWrongModule() throws IOException {
    final var zip = moduleZip("example.com/other", VERSION);

    assertThatThrownBy(
            () ->
                this.facade.upload(
                    this.context(MODULE, VERSION), new ByteArrayInputStream(zip), zip.length))
        .isNotNull();

    verify(this.moduleService, never())
        .publishModule(any(), any(), any(), any(), any(), any(), any());
    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), any());
  }

  private ProtocolContext listContext(final String escapedModulePath) {
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(this.repoInfo.getName())
            .relativePath(new RelativePath("/" + escapedModulePath + "/@v/list"))
            .repoInfo(this.repoInfo)
            .build();
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);

    return ctx;
  }

  private static StorageItemInfo infoFile(final String version) {
    return StorageItemInfo.builder().name(version + ".info").directory(false).build();
  }

  @Test
  @DisplayName("@v/list of a module without versions is not found, so the go command moves on")
  void versionListOfAnUnknownModuleIsNotFound() {
    when(this.storageService.listDirectory(any())).thenReturn(List.of());

    assertThatThrownBy(() -> this.facade.download(this.listContext(MODULE)))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("@v/list ignores directories and files that are not .info when it finds nothing")
  void versionListWithoutInfoFilesIsNotFound() {
    when(this.storageService.listDirectory(any()))
        .thenReturn(
            List.of(
                StorageItemInfo.builder().name("v1.0.0.info").directory(true).build(),
                StorageItemInfo.builder().name("v1.0.0.zip").directory(false).build()));

    assertThatThrownBy(() -> this.facade.download(this.listContext(MODULE)))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("@v/list lists the versions in semver order")
  void versionListIsSorted() throws IOException {
    when(this.storageService.listDirectory(any()))
        .thenReturn(List.of(infoFile("v0.10.0"), infoFile("v0.2.0")));

    final var body = this.facade.download(this.listContext(MODULE));

    assertThat(new String(body.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("v0.2.0\nv0.10.0");
  }

  @Test
  @DisplayName("@v/list of a mixed-case module falls back to its legacy lower-case listing")
  void versionListFallsBackToTheLegacyLowerCaseListing() throws IOException {
    final var captor = ArgumentCaptor.forClass(StoragePath.class);
    when(this.storageService.listDirectory(captor.capture()))
        .thenReturn(List.of())
        .thenReturn(List.of(infoFile("v1.0.0")));

    final var body = this.facade.download(this.listContext("example.com/!mod"));

    assertThat(new String(body.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("v1.0.0");
    assertThat(captor.getAllValues())
        .extracting(path -> path.getRelativePath().getPath())
        .containsExactly("/example.com/!mod/@v/", "/example.com/mod/@v/");
  }

  @Test
  @DisplayName("@v/list of a mixed-case module with no legacy listing either is not found")
  void versionListOfAnUnknownMixedCaseModuleIsNotFound() {
    when(this.storageService.listDirectory(any())).thenReturn(List.of());

    assertThatThrownBy(() -> this.facade.download(this.listContext("example.com/!mod")))
        .isInstanceOf(ItemNotFoundException.class);
    verify(this.storageService, times(2)).listDirectory(any());
  }
}
