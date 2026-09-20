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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * RPS-1058: the facade wrote a POM to storage and only then let the artifact service parse it, so a
 * rejected POM stayed in the repo and the usage counter never saw it. The POM is parsed first now.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractMavenProtocolFacade upload")
class AbstractMavenProtocolFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "maven";
  private static final String POM_PATH = "com/example/lib/1.0/lib-1.0.pom";
  private static final String VALID_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
      </project>
      """;
  private static final String MALFORMED_POM =
      "<project><modelVersion>4.0.0</modelVersion><groupId>";

  @Mock private MavenStorageService<UUID> storageService;
  @Mock private ArtifactService<UUID> artifactService;

  private TestFacade facade;
  private ProtocolContext context;
  private BaseRepoInfo<UUID> repoInfo;
  private final List<byte[]> stored = new ArrayList<>();

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

  private void storageReportsUsage(final long bytes) {
    when(this.storageService.writeInputStreamToPath(any(StoragePath.class), any(), anyString()))
        .thenAnswer(
            invocation -> {
              this.stored.add(invocation.<InputStream>getArgument(1).readAllBytes());
              return BaseUsages.ofDisk(bytes);
            });
  }

  private void deployIsAllowed() {
    when(this.artifactService.getDeployAndVersionType(any(), any()))
        .thenReturn(new MutablePair<>(ArtifactDeployType.NEW, ArtifactVersionType.RELEASE));
  }

  private void upload(final String body) throws Exception {
    final var bytes = body.getBytes(UTF_8);

    this.facade.upload(this.context, new ByteArrayInputStream(bytes), bytes.length);
  }

  @Test
  @DisplayName("stores nothing and reports no usage for a malformed POM")
  void rejectsAMalformedPomBeforeStoringIt() {
    requestFor(POM_PATH);
    deployIsAllowed();

    assertThatThrownBy(() -> upload(MALFORMED_POM))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("stores a valid POM byte for byte, then registers it and reports its usage")
  void storesAValidPomAndReportsItsUsage() throws Exception {
    requestFor(POM_PATH);
    deployIsAllowed();
    storageReportsUsage(VALID_POM.length());
    final Resource stored = new ByteArrayResource(VALID_POM.getBytes(UTF_8));
    when(this.storageService.getResource(anyString(), any(StoragePath.class))).thenReturn(stored);

    upload(VALID_POM);

    assertThat(this.stored).singleElement().isEqualTo(VALID_POM.getBytes(UTF_8));

    final var order = inOrder(this.storageService, this.artifactService);
    order
        .verify(this.artifactService)
        .checkDeploymentRules(any(), any(MutablePair.class), any(StoragePath.class));
    order
        .verify(this.storageService)
        .writeInputStreamToPath(any(StoragePath.class), any(), anyString());
    order
        .verify(this.artifactService)
        .createOrUpdateArtifact(any(), any(StoragePath.class), any(Resource.class));

    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage())
        .isEqualTo(VALID_POM.length());
  }

  @Test
  @DisplayName("does not parse the checksum of a POM, which is not XML")
  void storesAPomChecksumWithoutParsingIt() throws Exception {
    final var path = POM_PATH + ".sha1";
    requestFor(path);
    deployIsAllowed();
    storageReportsUsage(40);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("da39a3ee5e6b4b0d3255bfef95601890afd80709");

    assertThat(this.stored)
        .singleElement()
        .isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709".getBytes(UTF_8));
  }

  @Test
  @DisplayName("does not parse the signature of a POM, which is not XML")
  void storesAPomSignatureWithoutParsingIt() throws Exception {
    final var path = POM_PATH + ".asc";
    requestFor(path);
    deployIsAllowed();
    storageReportsUsage(20);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("-----BEGIN PGP SIGNATURE-----");

    assertThat(this.stored)
        .singleElement()
        .isEqualTo("-----BEGIN PGP SIGNATURE-----".getBytes(UTF_8));
  }

  @Test
  @DisplayName("streams a jar straight to storage and hands the scanner its coordinates")
  void storesAJarAndExposesItToTheScanner() throws Exception {
    final var path = "com/example/lib/1.0/lib-1.0.jar";
    requestFor(path);
    deployIsAllowed();
    storageReportsUsage(3);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("jar");

    assertThat(this.stored).singleElement().isEqualTo("jar".getBytes(UTF_8));
    assertThat(this.context.<String>getProperty("artifactName")).isEqualTo("com.example:lib");
    assertThat(this.context.<String>getProperty("artifactVersion")).isEqualTo("1.0");
  }

  @Test
  @DisplayName("stores nothing for a file whose deploy type cannot be worked out")
  void ignoresAFileWithoutADeployType() throws Exception {
    requestFor(POM_PATH);
    when(this.artifactService.getDeployAndVersionType(any(), any())).thenReturn(null);

    upload(VALID_POM);

    verifyNoInteractions(this.storageService);
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("buffers a maven-metadata.xml upload, checks it and stores it")
  void storesMetadataThroughTheBuffer() throws Exception {
    final var path = "com/example/lib/maven-metadata.xml";
    final var metadata = "<metadata/>";
    requestFor(path);
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), anyString()))
        .thenReturn(new MutablePair<>(ArtifactDeployType.NEW, ArtifactVersionType.RELEASE));
    storageReportsUsage(metadata.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(metadata.getBytes(UTF_8)));

    upload(metadata);

    assertThat(this.stored).singleElement().isEqualTo(metadata.getBytes(UTF_8));
    verify(this.artifactService).createOrUpdateArtifact(any(), any(), any());
  }

  @Test
  @DisplayName("passes an unreadable POM upload on as a failed request, storing nothing")
  void storesNothingWhenThePomCannotBeRead() {
    requestFor(POM_PATH);
    deployIsAllowed();
    final InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset");
          }
        };

    assertThatThrownBy(() -> this.facade.upload(this.context, failing, 10))
        .isInstanceOf(IOException.class);

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
  }
}
