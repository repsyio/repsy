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
package io.repsy.protocols.nuget.protocol.facades;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static io.repsy.protocols.nuget.NuGetTestContexts.repoInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetProtocolFacade.publish()")
class AbstractNuGetProtocolFacadeTest {

  @Mock private NuGetStorageService storageService;
  @Mock private NuGetPackageService<UUID> packageService;

  private AbstractNuGetProtocolFacade<UUID> facade;

  @BeforeEach
  void setUp() {
    facade = new TestFacade(storageService, packageService);
  }

  static class TestFacade extends AbstractNuGetProtocolFacade<UUID> {

    TestFacade(final NuGetStorageService s, final NuGetPackageService<UUID> p) {
      super(s, p);
    }
  }

  private static InputStream nupkg(final String id, final String version) throws IOException {
    final var nuspec =
        "<package><metadata><id>%s</id><version>%s</version></metadata></package>"
            .formatted(id, version);
    final var bytes = new ByteArrayOutputStream();
    try (final var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(id + ".nuspec"));
      zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }

  @Test
  @DisplayName("stores the package and records its lower-cased, normalized storage path")
  void recordsStoragePathInContext() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    final var ctx = context("/v3/package", repoInfo);
    final var usages = BaseUsages.ofDisk(42);
    final var packageId = UUID.randomUUID();
    when(packageService.versionExists(repoInfo, "Some.Package", "1.0.0")).thenReturn(false);
    when(packageService.findOrCreatePackage(repoInfo, "Some.Package")).thenReturn(packageId);
    publishRunsFilesWriter(false);
    when(storageService.writePackage(
            eq(repoInfo.getStorageKey()),
            eq("Some.Package"),
            eq("1.0.0"),
            any(InputStream.class),
            any(byte[].class)))
        .thenReturn(usages);

    facade.publish(ctx, nupkg("Some.Package", "1.0"));

    assertThat(ctx.<String>getProperty("artifactName")).isEqualTo("Some.Package");
    assertThat(ctx.<String>getProperty("artifactVersion")).isEqualTo("1.0.0");
    assertThat(ctx.<String>getProperty("storagePath"))
        .isEqualTo("packages/some.package/1.0.0/some.package.1.0.0.nupkg");
    assertThat(ctx.<BaseUsages>getProperty("usages")).isSameAs(usages);
    verify(packageService)
        .publishVersion(eq(repoInfo), eq(packageId), eq("1.0.0"), any(), any(), any());
  }

  @Test
  @DisplayName("does not touch storage when the package row cannot be written")
  void leavesStorageAloneWhenRowFails() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    final var packageId = UUID.randomUUID();
    when(packageService.findOrCreatePackage(repoInfo, "Some.Package")).thenReturn(packageId);
    when(packageService.publishVersion(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("row rejected"));

    assertThatThrownBy(
            () -> facade.publish(context("/v3/package", repoInfo), nupkg("Some.Package", "1.0.0")))
        .isInstanceOf(IllegalStateException.class);

    verify(storageService, never()).writePackage(any(), any(), any(), any(), any());
    verify(storageService, never()).deletePackageVersion(any(), any(), any());
  }

  @Test
  @DisplayName("removes the partly written files of a new version when storing fails")
  void discardsPartialFilesOfNewVersion() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    final var failure = new IOException("disk full");
    when(packageService.findOrCreatePackage(repoInfo, "Some.Package"))
        .thenReturn(UUID.randomUUID());
    publishRunsFilesWriter(false);
    when(storageService.writePackage(any(), any(), any(), any(InputStream.class), any()))
        .thenThrow(failure);

    assertThatThrownBy(
            () -> facade.publish(context("/v3/package", repoInfo), nupkg("Some.Package", "1.0.0")))
        .isSameAs(failure);

    verify(storageService).deletePackageVersion(repoInfo.getStorageKey(), "Some.Package", "1.0.0");
  }

  @Test
  @DisplayName("keeps the files of the version being replaced when storing fails")
  void keepsFilesOfReplacedVersion() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    final var failure = new IOException("disk full");
    when(packageService.findOrCreatePackage(repoInfo, "Some.Package"))
        .thenReturn(UUID.randomUUID());
    publishRunsFilesWriter(true);
    when(storageService.writePackage(any(), any(), any(), any(InputStream.class), any()))
        .thenThrow(failure);

    assertThatThrownBy(
            () -> facade.publish(context("/v3/package", repoInfo), nupkg("Some.Package", "1.0.0")))
        .isSameAs(failure);

    verify(storageService, never()).deletePackageVersion(any(), any(), any());
  }

  @Test
  @DisplayName("still reports the storing failure when removing the partial files fails too")
  void reportsStoringFailureWhenCleanupFails() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    final var failure = new IOException("disk full");
    final var cleanupFailure = new IOException("nothing to delete");
    when(packageService.findOrCreatePackage(repoInfo, "Some.Package"))
        .thenReturn(UUID.randomUUID());
    publishRunsFilesWriter(false);
    when(storageService.writePackage(any(), any(), any(), any(InputStream.class), any()))
        .thenThrow(failure);
    when(storageService.deletePackageVersion(any(), any(), any())).thenThrow(cleanupFailure);

    assertThatThrownBy(
            () -> facade.publish(context("/v3/package", repoInfo), nupkg("Some.Package", "1.0.0")))
        .isSameAs(failure)
        .hasSuppressedException(cleanupFailure);
  }

  /** The real service runs the writer inside its transaction; the mock runs it in place. */
  private void publishRunsFilesWriter(final boolean replacesExisting) throws IOException {
    when(packageService.publishVersion(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<NuGetPackageService.PackageFilesWriter>getArgument(5)
                    .write(replacesExisting));
  }

  @Test
  @DisplayName("rejects an existing version with 409 unless overriding is allowed")
  void rejectsExistingVersion() throws IOException {
    final BaseRepoInfo<UUID> repoInfo = repoInfo();
    when(packageService.versionExists(repoInfo, "Some.Package", "1.0.0")).thenReturn(true);

    assertThatThrownBy(
            () -> facade.publish(context("/v3/package", repoInfo), nupkg("Some.Package", "1.0.0")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verify(storageService, never()).writePackage(any(), any(), any(), any(), any());
  }
}
