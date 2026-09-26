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
package io.repsy.protocols.maven.protocol.facades;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.resources.SynthesizedFileResource;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactMetadataSynthesizer;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * RPS-1369: a request for the artifact-level {@code maven-metadata.xml} (or one of its checksums)
 * that finds no stored file is answered from the registered versions, and nothing else is.
 * RPS-1438: the group-level one, which has the same shape, is answered from the registered plugins
 * when no artifact of that group and name is registered.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractMavenProtocolFacade download (RPS-1369, RPS-1438)")
class AbstractMavenProtocolFacadeDownloadTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "maven";
  private static final String XML_PATH = "/com/acme/lib/maven-metadata.xml";
  private static final String GROUP_XML_PATH = "/com/acme/maven-metadata.xml";
  private static final List<RegisteredPlugin> PLUGINS =
      List.of(new RegisteredPlugin("hello-maven-plugin", "Hello", "hello"));
  private static final List<RegisteredVersion> VERSIONS =
      List.of(
          new RegisteredVersion("1.10", Instant.parse("2026-09-21T10:10:10Z")),
          new RegisteredVersion("1.2", Instant.parse("2026-09-20T10:10:10Z")));

  @Mock private MavenStorageService<UUID> storageService;
  @Mock private ArtifactService<UUID> artifactService;

  private AbstractMavenProtocolFacade<UUID> facade;
  private ProtocolContext context;
  private BaseRepoInfo<UUID> repoInfo;

  private static class TestFacade extends AbstractMavenProtocolFacade<UUID> {

    TestFacade(
        final MavenStorageService<UUID> storageService,
        final ArtifactService<UUID> artifactService) {
      super(storageService, artifactService);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade = new TestFacade(this.storageService, this.artifactService);
    this.repoInfo = new BaseRepoInfo<>();
    this.repoInfo.setId(REPO_ID);
    this.repoInfo.setStorageKey(REPO_ID);
    this.repoInfo.setName(REPO_NAME);
  }

  private void requestFor(final String path) {
    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath(path))
            .repoInfo(this.repoInfo)
            .build());
  }

  private void nothingIsStored() {
    when(this.storageService.getResource(eq(REPO_NAME), any(StoragePath.class)))
        .thenThrow(new ItemNotFoundException("itemNotFound"));
  }

  private void versionsAreRegistered() {
    when(this.artifactService.getRegisteredVersions(this.repoInfo, "com.acme", "lib"))
        .thenReturn(VERSIONS);
  }

  /** {@code StoragePath} has no equality, so the path it holds is compared. */
  private static boolean isTheXml(final StoragePath storagePath) {
    return storagePath.getPath().equals(REPO_ID + "/com/acme/lib/maven-metadata.xml");
  }

  private static byte[] bytesOf(final Resource resource) throws Exception {
    return resource.getContentAsByteArray();
  }

  @Test
  @DisplayName("serves the stored file, and asks for no version, when there is one")
  void storedFileWins() {
    final var stored = new ByteArrayResource("<metadata/>".getBytes(UTF_8));
    when(this.storageService.getResource(eq(REPO_NAME), any(StoragePath.class))).thenReturn(stored);
    requestFor(XML_PATH);

    assertThat(this.facade.download(this.context)).isSameAs(stored);

    verify(this.artifactService, never()).getRegisteredVersions(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("generates the file from the registered versions when none is stored")
  void generatesTheFile() throws Exception {
    nothingIsStored();
    versionsAreRegistered();
    requestFor(XML_PATH);

    final var resource = this.facade.download(this.context);

    assertThat(resource).isInstanceOf(SynthesizedFileResource.class);
    assertThat(resource.getFilename()).isEqualTo("maven-metadata.xml");

    final var metadata = ArtifactUtils.readMetadata(bytesOf(resource));

    assertThat(metadata.getGroupId()).isEqualTo("com.acme");
    assertThat(metadata.getArtifactId()).isEqualTo("lib");
    assertThat(metadata.getVersioning().getVersions()).containsExactly("1.2", "1.10");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("1.10");
    assertThat(metadata.getVersioning().getLastUpdated()).isEqualTo("20260921101010");

    // Nothing is stored for it: it is neither written nor looked up beyond the miss.
    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
    verify(this.storageService, never()).exists(any(), anyString());
  }

  @Test
  @DisplayName("keeps the missing-file answer when the artifact has no registered version")
  void unknownArtifact() {
    nothingIsStored();
    when(this.artifactService.getRegisteredVersions(this.repoInfo, "com.acme", "lib"))
        .thenReturn(List.of());
    requestFor(XML_PATH);

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha1", "sha256", "sha512"})
  @DisplayName("generates a checksum of the generated file when the file is not stored either")
  void generatesTheChecksum(final String algorithm) throws Exception {
    nothingIsStored();
    versionsAreRegistered();
    when(this.storageService.exists(
            argThat(AbstractMavenProtocolFacadeDownloadTest::isTheXml), eq(REPO_NAME)))
        .thenReturn(false);

    requestFor(XML_PATH);
    final var xml = bytesOf(this.facade.download(this.context));

    requestFor(XML_PATH + "." + algorithm);
    final var resource = this.facade.download(this.context);

    assertThat(resource).isInstanceOf(SynthesizedFileResource.class);
    assertThat(resource.getFilename()).isEqualTo("maven-metadata.xml." + algorithm);
    assertThat(bytesOf(resource)).isEqualTo(ArtifactMetadataSynthesizer.checksum(xml, algorithm));
    assertThat(new String(bytesOf(resource), UTF_8)).isEqualTo(hex(xml, algorithm));
  }

  @Test
  @DisplayName(
      "does not hash a file it does not serve: a checksum is missing when the file is stored")
  void noChecksumOfAStoredFile() {
    nothingIsStored();
    when(this.storageService.exists(
            argThat(AbstractMavenProtocolFacadeDownloadTest::isTheXml), eq(REPO_NAME)))
        .thenReturn(true);
    requestFor(XML_PATH + ".sha1");

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.artifactService, never()).getRegisteredVersions(any(), anyString(), anyString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/com/acme/lib/maven-metadata.xml.asc",
        "/com/acme/lib/maven-metadata.xml.asc.sha1",
        "/com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml",
        "/com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1",
        "/lib/maven-metadata.xml",
        "/com/acme/lib/1.0/lib-1.0.jar",
        "/com/acme/lib/"
      })
  @DisplayName("keeps the missing-file answer for any other path, and asks for no version")
  void anyOtherPath(final String path) {
    nothingIsStored();
    requestFor(path);

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.artifactService, never()).getRegisteredVersions(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("registers no version unless the implementation says so")
  void defaultRegistersNothing() {
    final ArtifactService<UUID> service =
        mock(ArtifactService.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    assertThat(service.getRegisteredVersions(this.repoInfo, "com.acme", "lib")).isEmpty();
  }

  private void pluginsAreRegistered() {
    when(this.artifactService.getRegisteredPlugins(this.repoInfo, "com.acme")).thenReturn(PLUGINS);
  }

  private static boolean isTheGroupXml(final StoragePath storagePath) {
    return storagePath != null
        && storagePath.getPath().equals(REPO_ID + "/com/acme/maven-metadata.xml");
  }

  @Test
  @DisplayName("serves a stored group-level file as stored and asks for no plugin")
  void storedGroupFileWins() {
    final var stored = new ByteArrayResource("<metadata/>".getBytes(UTF_8));
    when(this.storageService.getResource(eq(REPO_NAME), any(StoragePath.class))).thenReturn(stored);
    requestFor(GROUP_XML_PATH);

    assertThat(this.facade.download(this.context)).isSameAs(stored);

    verify(this.artifactService, never()).getRegisteredPlugins(any(), anyString());
  }

  @Test
  @DisplayName("generates the group-level file from the registered plugins when none is stored")
  void generatesTheGroupFile() throws Exception {
    nothingIsStored();
    pluginsAreRegistered();
    requestFor(GROUP_XML_PATH);

    final var resource = this.facade.download(this.context);

    assertThat(resource).isInstanceOf(SynthesizedFileResource.class);
    assertThat(resource.getFilename()).isEqualTo("maven-metadata.xml");
    assertThat(bytesOf(resource)).isEqualTo(ArtifactMetadataSynthesizer.groupMetadataXml(PLUGINS));

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
    verify(this.storageService, never()).exists(any(), anyString());
  }

  @Test
  @DisplayName("answers the artifact-level file first when an artifact has the name of the group")
  void artifactLevelComesFirst() throws Exception {
    nothingIsStored();
    when(this.artifactService.getRegisteredVersions(this.repoInfo, "com", "acme"))
        .thenReturn(VERSIONS);
    requestFor(GROUP_XML_PATH);

    final var metadata = ArtifactUtils.readMetadata(bytesOf(this.facade.download(this.context)));

    assertThat(metadata.getGroupId()).isEqualTo("com");
    assertThat(metadata.getArtifactId()).isEqualTo("acme");
    assertThat(metadata.getPlugins()).isEmpty();

    verify(this.artifactService, never()).getRegisteredPlugins(any(), anyString());
  }

  @Test
  @DisplayName("keeps the missing-file answer when the group has no registered plugin")
  void groupWithoutPlugins() {
    nothingIsStored();
    when(this.artifactService.getRegisteredPlugins(this.repoInfo, "com.acme"))
        .thenReturn(List.of());
    requestFor(GROUP_XML_PATH);

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha1", "sha256", "sha512"})
  @DisplayName("generates a checksum of the group-level file when the file is not stored either")
  void generatesTheGroupChecksum(final String algorithm) throws Exception {
    nothingIsStored();
    pluginsAreRegistered();
    when(this.storageService.exists(
            argThat(AbstractMavenProtocolFacadeDownloadTest::isTheGroupXml), eq(REPO_NAME)))
        .thenReturn(false);
    requestFor(GROUP_XML_PATH + "." + algorithm);

    final var resource = this.facade.download(this.context);

    assertThat(resource.getFilename()).isEqualTo("maven-metadata.xml." + algorithm);
    assertThat(new String(bytesOf(resource), UTF_8))
        .isEqualTo(hex(ArtifactMetadataSynthesizer.groupMetadataXml(PLUGINS), algorithm));
  }

  @Test
  @DisplayName("does not hash a stored group-level file: its checksum is missing when it is stored")
  void noChecksumOfAStoredGroupFile() {
    nothingIsStored();
    // The artifact-level and the group-level reading of the path name the same file.
    when(this.storageService.exists(
            argThat(AbstractMavenProtocolFacadeDownloadTest::isTheGroupXml), eq(REPO_NAME)))
        .thenReturn(true);
    requestFor(GROUP_XML_PATH + ".sha1");

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.artifactService, never()).getRegisteredVersions(any(), anyString(), anyString());
    verify(this.artifactService, never()).getRegisteredPlugins(any(), anyString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/com/acme/maven-metadata.xml.asc",
        "/com/acme/maven-metadata.xml.asc.sha1",
        "/com/acme/1.0-SNAPSHOT/maven-metadata.xml",
        "/maven-metadata.xml",
        "/com/acme/lib-1.0.pom",
        "/com/acme/"
      })
  @DisplayName("asks for no plugin for any other path")
  void noPluginForAnyOtherPath(final String path) {
    nothingIsStored();
    requestFor(path);

    assertThatThrownBy(() -> this.facade.download(this.context))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.artifactService, never()).getRegisteredPlugins(any(), anyString());
  }

  @Test
  @DisplayName("registers no plugin unless the implementation says so")
  void defaultRegistersNoPlugin() {
    final ArtifactService<UUID> service =
        mock(ArtifactService.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    assertThat(service.getRegisteredPlugins(this.repoInfo, "com.acme")).isEmpty();
  }

  private static String hex(final byte[] content, final String algorithm) {
    return switch (algorithm) {
      case "md5" -> DigestUtils.md5Hex(content);
      case "sha1" -> DigestUtils.sha1Hex(content);
      case "sha256" -> DigestUtils.sha256Hex(content);
      case "sha512" -> DigestUtils.sha512Hex(content);
      case null, default -> throw new IllegalStateException(algorithm);
    };
  }
}
