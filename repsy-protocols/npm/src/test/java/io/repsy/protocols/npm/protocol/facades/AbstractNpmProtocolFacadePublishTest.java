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
package io.repsy.protocols.npm.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService.PublishKind;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.util.Pair;

/**
 * RPS-1124: {@link AbstractNpmProtocolFacade#publish} hands the files to {@link
 * NpmPackageService#publishVersion}, which writes the rows first, and removes what a failed write
 * of a new version left in storage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmProtocolFacade publish (RPS-1124)")
class AbstractNpmProtocolFacadePublishTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final String PACKAGE = "demo";
  private static final String VERSION = "1.2.3";
  private static final Path BASE_PATH = Path.of(PACKAGE);
  private static final byte[] PREVIOUS_METADATA = "{\"name\":\"demo\"}".getBytes();

  @Mock private NpmPackageService<UUID> packageService;
  @Mock private AbstractNpmStorageService storageService;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractNpmProtocolFacade<UUID> {

    TestFacade(
        final NpmPackageService<UUID> packageService, final AbstractNpmStorageService storage) {
      super(packageService, storage);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade = new TestFacade(this.packageService, this.storageService);

    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/"))
            .repoInfo(repoInfo)
            .build());

    when(this.storageService.getPackageBasePath(null, PACKAGE)).thenReturn(BASE_PATH);
  }

  private static Map<String, Object> payload() {
    final var version = new LinkedHashMap<String, Object>();
    version.put("name", PACKAGE);
    version.put("version", VERSION);

    final var versions = new LinkedHashMap<String, Object>();
    versions.put(VERSION, version);

    final var distTags = new LinkedHashMap<String, Object>();
    distTags.put("latest", VERSION);

    final var payload = new LinkedHashMap<String, Object>();
    payload.put("_id", PACKAGE);
    payload.put("name", PACKAGE);
    payload.put("dist-tags", distTags);
    payload.put("versions", versions);

    return payload;
  }

  /** Makes the mocked service run the file writer like the real one does after the rows. */
  private void publishRuns(final PublishKind kind) throws Exception {
    when(this.packageService.publishVersion(any(), any(), eq(PACKAGE), eq(VERSION), any(), any()))
        .thenAnswer(
            invocation -> invocation.<NpmPackageService.VersionWriter>getArgument(5).write(kind));
  }

  private void publish(final Map<String, Object> payload) throws IOException {
    this.facade.publish(this.context, null, PACKAGE, payload);
  }

  @Test
  @DisplayName("a new package is processed as a whole and stored with its metadata")
  void storesANewPackage() throws Exception {
    final var payload = payload();
    final var usages = BaseUsages.ofDisk(42L);
    this.publishRuns(PublishKind.NEW_PACKAGE);
    when(this.storageService.writeTarballAndMetadata(
            REPO_ID, REPO_NAME, payload, BASE_PATH, PACKAGE, VERSION))
        .thenReturn(usages);

    this.publish(payload);

    verify(this.storageService).processPackagePayload(payload, REPO_NAME);
    verify(this.storageService, never()).readMetadataBytes(any(), any(), any());
    verify(this.storageService, never())
        .discardPublishedVersion(any(), any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isSameAs(usages);
    assertThat(this.context.<String>getProperty("artifactName")).isEqualTo(PACKAGE);
    assertThat(this.context.<String>getProperty("artifactVersion")).isEqualTo(VERSION);
    assertThat(this.context.<String>getProperty("storagePath"))
        .isEqualTo(Path.of(PACKAGE, "demo-1.2.3.tgz").toString());
  }

  @Test
  @DisplayName("a new version is merged into the stored metadata that was read under the lock")
  void storesANewVersion() throws Exception {
    final var payload = payload();
    final var merged = new LinkedHashMap<String, Object>();
    final var usages = BaseUsages.ofDisk(7L);
    this.publishRuns(PublishKind.NEW_VERSION);
    when(this.storageService.readMetadataBytes(REPO_ID, REPO_NAME, BASE_PATH))
        .thenReturn(PREVIOUS_METADATA);
    when(this.storageService.processVersionPayload(payload, BASE_PATH, REPO_ID, REPO_NAME))
        .thenReturn(Pair.of(Pair.of(1L, 2L), merged));
    when(this.storageService.writeTarballAndMetadata(
            REPO_ID, REPO_NAME, merged, BASE_PATH, PACKAGE, VERSION))
        .thenReturn(usages);

    this.publish(payload);

    verify(this.storageService, never()).processPackagePayload(any(), any());
    verify(this.storageService, never())
        .discardPublishedVersion(any(), any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isSameAs(usages);
  }

  @Test
  @DisplayName("replaces a tarball that has no version row (RPS-1272)")
  void replacesAnOrphanedTarball() throws Exception {
    final var payload = payload();
    final var usages = BaseUsages.ofDisk(42L);
    this.publishRuns(PublishKind.NEW_VERSION);
    when(this.storageService.tarballExists(REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, VERSION))
        .thenReturn(true);
    when(this.storageService.processVersionPayload(any(), any(), any(), any()))
        .thenReturn(Pair.of(Pair.of(1L, 2L), payload));
    when(this.storageService.writeTarballAndMetadata(
            REPO_ID, REPO_NAME, payload, BASE_PATH, PACKAGE, VERSION))
        .thenReturn(usages);

    this.publish(payload);

    verify(this.storageService)
        .writeTarballAndMetadata(REPO_ID, REPO_NAME, payload, BASE_PATH, PACKAGE, VERSION);
    assertThat(this.context.<BaseUsages>getProperty("usages")).isSameAs(usages);
  }

  @Test
  @DisplayName("does not look for an orphan when it replaces a version that has its rows")
  void doesNotLookForAnOrphanWhenReplacing() throws Exception {
    final var payload = payload();
    this.publishRuns(PublishKind.REPLACES_VERSION);
    when(this.storageService.processVersionPayload(any(), any(), any(), any()))
        .thenReturn(Pair.of(Pair.of(1L, 2L), payload));
    when(this.storageService.writeTarballAndMetadata(any(), any(), any(), any(), any(), any()))
        .thenReturn(BaseUsages.ofDisk(1L));

    this.publish(payload);

    verify(this.storageService, never()).tarballExists(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("does not touch storage when the rows are rejected, and reports nothing")
  void writesNothingWhenTheRowsAreRejected() throws Exception {
    when(this.packageService.publishVersion(any(), any(), any(), any(), any(), any()))
        .thenThrow(new AccessNotAllowedException("packageVersionAlreadyExists"));

    assertThatThrownBy(() -> this.publish(payload())).isInstanceOf(AccessNotAllowedException.class);

    verify(this.storageService, never())
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());
    verify(this.storageService, never())
        .discardPublishedVersion(any(), any(), any(), any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
    assertThat(this.context.<String>getProperty("artifactName")).isNull();
  }

  @Test
  @DisplayName("removes the files of a new package when the write fails")
  void removesTheFilesOfANewPackage() throws Exception {
    final var failure = new IllegalStateException("disk full");
    this.publishRuns(PublishKind.NEW_PACKAGE);
    when(this.storageService.writeTarballAndMetadata(any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.publish(payload())).isSameAs(failure);

    verify(this.storageService)
        .discardPublishedVersion(REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, VERSION, null);
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName(
      "puts the metadata back and removes the tarball of a new version when the write fails")
  void restoresTheMetadataOfANewVersion() throws Exception {
    final var failure = new IOException("disk full");
    this.publishRuns(PublishKind.NEW_VERSION);
    when(this.storageService.readMetadataBytes(REPO_ID, REPO_NAME, BASE_PATH))
        .thenReturn(PREVIOUS_METADATA);
    when(this.storageService.processVersionPayload(any(), any(), any(), any()))
        .thenReturn(Pair.of(Pair.of(1L, 2L), new LinkedHashMap<>()));
    when(this.storageService.writeTarballAndMetadata(any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.publish(payload())).isSameAs(failure);

    verify(this.storageService)
        .discardPublishedVersion(
            REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, VERSION, PREVIOUS_METADATA);
  }

  @Test
  @DisplayName("removes what a failed new version left when the payload cannot be processed")
  void removesTheFilesWhenProcessingFails() throws Exception {
    final var failure = new URISyntaxException("bad", "not a tarball url");
    this.publishRuns(PublishKind.NEW_PACKAGE);
    when(this.storageService.processPackagePayload(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> this.publish(payload())).isInstanceOf(BadRequestException.class);

    verify(this.storageService)
        .discardPublishedVersion(REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, VERSION, null);
    verify(this.storageService, never())
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("keeps the files of the version being replaced when the write fails")
  void keepsTheFilesOfAReplacedVersion() throws Exception {
    final var failure = new IllegalStateException("disk full");
    this.publishRuns(PublishKind.REPLACES_VERSION);
    when(this.storageService.processVersionPayload(any(), any(), any(), any()))
        .thenReturn(Pair.of(Pair.of(1L, 2L), new LinkedHashMap<>()));
    when(this.storageService.writeTarballAndMetadata(any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.publish(payload())).isSameAs(failure);

    verify(this.storageService, never()).readMetadataBytes(any(), any(), any());
    verify(this.storageService, never())
        .discardPublishedVersion(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("reports the write failure, with the cleanup failure attached, when both fail")
  void keepsTheWriteFailureWhenTheCleanupFails() throws Exception {
    final var failure = new IllegalStateException("disk full");
    final var cleanupFailure = new IOException("trash unavailable");
    this.publishRuns(PublishKind.NEW_PACKAGE);
    when(this.storageService.writeTarballAndMetadata(any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);
    doThrow(cleanupFailure)
        .when(this.storageService)
        .discardPublishedVersion(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> this.publish(payload()))
        .isSameAs(failure)
        .hasSuppressedException(cleanupFailure);
  }

  @Test
  @DisplayName("a payload that is not shaped like a publish is a bad request")
  void malformedPayloadIsABadRequest() throws Exception {
    final var payload = payload();
    payload.put("versions", "not a map");

    assertThatThrownBy(() -> this.publish(payload)).isInstanceOf(BadRequestException.class);

    verify(this.packageService, never()).publishVersion(any(), any(), any(), any(), any(), any());
  }
}
