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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.MavenUploadLimits;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * RPS-1058: the facade wrote a POM to storage and only then let the artifact service parse it, so a
 * rejected POM stayed in the repo and the usage counter never saw it. The POM is parsed first now.
 *
 * <p>RPS-1186: the same for a POM signature. It was stored and only then verified, and a refused
 * one was rolled back by deleting the whole version (and the artifact, and the group, when it was
 * the last one). It is verified before it is stored now, so a refused one changes nothing.
 *
 * <p>RPS-1193: a POM whose groupId is not the one of its path was stored and answered 200 but never
 * registered. It is refused before it is stored now, like a malformed one.
 *
 * <p>RPS-1183: a checksum is judged by the file it belongs to, so one of a refused kind is refused
 * before it is stored; a metadata checksum is classified by its path, its body being a hash.
 *
 * <p>RPS-1185: the {@code .asc} signature of a metadata file is handled like a metadata checksum,
 * classified by its path and never parsed, and it is not a POM signature, so it is never verified.
 *
 * <p>RPS-1121: a metadata-family file, a POM signature and a POM are each capped ({@link
 * MavenUploadLimits}) before they are read whole or spooled, so an oversized one is refused with a
 * 400 and stores nothing.
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
  private static final String ARMORED_SIGNATURE =
      "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n";
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
  @DisplayName("stores nothing and reports no usage for a POM of another group")
  void rejectsAPomOfAnotherGroupBeforeStoringIt() {
    requestFor(POM_PATH);
    deployIsAllowed();

    assertThatThrownBy(() -> upload(VALID_POM.replace("com.example", "org.other")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pomGroupIdMismatch");

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("stores a POM that inherits its groupId from the parent of its own group")
  void storesAPomWithAnInheritedGroupId() throws Exception {
    final var inherited =
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1</version>
          </parent>
          <artifactId>lib</artifactId>
          <version>1.0</version>
        </project>
        """;
    requestFor(POM_PATH);
    deployIsAllowed();
    storageReportsUsage(inherited.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(inherited.getBytes(UTF_8)));

    upload(inherited);

    assertThat(this.stored).singleElement().isEqualTo(inherited.getBytes(UTF_8));
    verify(this.artifactService).createOrUpdateArtifact(any(), any(StoragePath.class), any());
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
  @DisplayName("verifies a POM signature against the stored POM before it stores the signature")
  void verifiesAPomSignatureBeforeStoringIt() throws Exception {
    final var signature = "-----BEGIN PGP SIGNATURE-----\nabc\n-----END PGP SIGNATURE-----\n";
    requestFor(POM_PATH + ".asc");
    deployIsAllowed();
    storageReportsUsage(signature.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(signature.getBytes(UTF_8)));

    upload(signature);

    final var verified = ArgumentCaptor.forClass(Resource.class);
    final var order = inOrder(this.storageService, this.artifactService);
    order
        .verify(this.artifactService)
        .checkDeploymentRules(any(), any(MutablePair.class), any(StoragePath.class));
    order
        .verify(this.artifactService)
        .verifySignature(any(), any(StoragePath.class), verified.capture());
    order
        .verify(this.storageService)
        .writeInputStreamToPath(any(StoragePath.class), any(), anyString());
    order
        .verify(this.artifactService)
        .createOrUpdateArtifact(any(), any(StoragePath.class), any(Resource.class));

    assertThat(verified.getValue().getContentAsByteArray()).isEqualTo(signature.getBytes(UTF_8));
    assertThat(this.stored).singleElement().isEqualTo(signature.getBytes(UTF_8));
    assertThat(this.context.<BaseUsages>getProperty("usages").getDiskUsage())
        .isEqualTo(signature.length());
  }

  @Test
  @DisplayName("stores nothing and reports no usage for a refused POM signature")
  void storesNothingWhenThePomSignatureIsRefused() {
    requestFor(POM_PATH + ".asc");
    deployIsAllowed();
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.artifactService)
        .verifySignature(any(), any(StoragePath.class), any(Resource.class));

    assertThatThrownBy(() -> upload("not a signature"))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("artifactSignatureNotVerified");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName(
      "pins the current behaviour: only a POM signature is verified, a jar signature is not")
  void doesNotVerifyAJarSignature() throws Exception {
    requestFor("com/example/lib/1.0/lib-1.0.jar.asc");
    deployIsAllowed();
    storageReportsUsage(3);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("sig");

    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.stored).singleElement().isEqualTo("sig".getBytes(UTF_8));
  }

  @Test
  @DisplayName("does not verify the checksum of a POM signature")
  void doesNotVerifyTheChecksumOfAPomSignature() throws Exception {
    requestFor(POM_PATH + ".asc.sha1");
    deployIsAllowed();
    storageReportsUsage(40);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("da39a3ee5e6b4b0d3255bfef95601890afd80709");

    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.stored).hasSize(1);
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
  @DisplayName(
      "streams a jar of an artifactId containing \".pom\" straight to storage, never as a POM"
          + " (RPS-1196)")
  void streamsAJarOfAnArtifactIdContainingPomToStorage() throws Exception {
    final var path = "com/example/bar.pom.utils/1.0/bar.pom.utils-1.0.jar";
    final var body = "PK\u0003\u0004binarycontentnotxmlatall";
    requestFor(path);
    deployIsAllowed();
    storageReportsUsage(body.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload(body);

    assertThat(this.stored).singleElement().isEqualTo(body.getBytes(UTF_8));
    verify(this.artifactService, never()).verifySignature(any(), any(), any());
  }

  @Test
  @DisplayName("does not verify the jar signature of an artifactId containing \".pom\" (RPS-1196)")
  void doesNotVerifyAJarSignatureOfAnArtifactIdContainingPom() throws Exception {
    requestFor("com/example/bar.pom.utils/1.0/bar.pom.utils-1.0.jar.asc");
    deployIsAllowed();
    storageReportsUsage(3);
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(new byte[0]));

    upload("sig");

    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.stored).singleElement().isEqualTo("sig".getBytes(UTF_8));
  }

  @Test
  @DisplayName("verifies the POM signature of an artifactId containing \".pom\" (RPS-1196)")
  void verifiesThePomSignatureOfAnArtifactIdContainingPom() throws Exception {
    final var signature = "-----BEGIN PGP SIGNATURE-----\nabc\n-----END PGP SIGNATURE-----\n";
    requestFor("com/example/bar.pom.utils/1.0/bar.pom.utils-1.0.pom.asc");
    deployIsAllowed();
    storageReportsUsage(signature.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(signature.getBytes(UTF_8)));

    upload(signature);

    verify(this.artifactService)
        .verifySignature(any(), any(StoragePath.class), any(Resource.class));
    assertThat(this.stored).singleElement().isEqualTo(signature.getBytes(UTF_8));
  }

  @Test
  @DisplayName("rejects a malformed POM of an artifactId containing \".pom\" (RPS-1196)")
  void rejectsAMalformedPomOfAnArtifactIdContainingPom() {
    requestFor("com/example/bar.pom.utils/1.0/bar.pom.utils-1.0.pom");
    deployIsAllowed();

    assertThatThrownBy(() -> upload(MALFORMED_POM))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
  }

  @Test
  @DisplayName("refuses a path outside the artifact layout before checking rules or storing it")
  void refusesANonArtifactPathBeforeStoringIt() {
    requestFor("io/stray.txt");
    when(this.artifactService.getDeployAndVersionType(any(), any()))
        .thenThrow(new BadRequestException("invalidArtifactPath"));

    assertThatThrownBy(() -> upload("hello"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalidArtifactPath");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never()).checkDeploymentRules(any(), any(), any());
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("buffers a maven-metadata.xml upload, checks it and stores it")
  void storesMetadataThroughTheBuffer() throws Exception {
    final var path = "com/example/lib/maven-metadata.xml";
    final var metadata = "<metadata/>";
    requestFor(path);
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), any(StoragePath.class)))
        .thenReturn(new MutablePair<>(ArtifactDeployType.NEW, ArtifactVersionType.RELEASE));
    storageReportsUsage(metadata.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(metadata.getBytes(UTF_8)));

    upload(metadata);

    assertThat(this.stored).singleElement().isEqualTo(metadata.getBytes(UTF_8));
    verify(this.artifactService).createOrUpdateArtifact(any(), any(), any());
  }

  @Test
  @DisplayName("hands a metadata checksum to the metadata classifier with its path, unparsed")
  void passesAMetadataChecksumToTheMetadataClassifierWithItsPath() throws Exception {
    final var path = "com/example/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1";
    final var hash = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
    requestFor(path);
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), any(StoragePath.class)))
        .thenReturn(new MutablePair<>(null, ArtifactVersionType.SNAPSHOT));
    storageReportsUsage(hash.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(hash.getBytes(UTF_8)));

    upload(hash);

    final var storagePath = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.artifactService)
        .getDeployAndVersionTypesByMetadataTypeFiles(
            any(), eq(hash.getBytes(UTF_8)), storagePath.capture());
    assertThat(storagePath.getValue().getRelativePath().getPath()).isEqualTo(path);
    verify(this.artifactService, never()).getDeployAndVersionType(any(), any());
    assertThat(this.stored).singleElement().isEqualTo(hash.getBytes(UTF_8));
  }

  @Test
  @DisplayName("stores nothing and reports no usage for a checksum whose kind is refused")
  void storesNothingForAChecksumWhoseKindIsRefused() {
    requestFor("com/example/lib/1.0/lib-1.0.jar.sha1");
    deployIsAllowed();
    doThrow(new AccessNotAllowedException("releaseVersionsAreProhibited"))
        .when(this.artifactService)
        .checkDeploymentRules(any(), any(), any());

    assertThatThrownBy(() -> upload("da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName(
      "hands a metadata signature to the metadata classifier, unparsed, and never verifies it")
  void passesAMetadataSignatureToTheMetadataClassifierUnparsedAndNeverVerifiesIt()
      throws Exception {
    final var path = "com/example/lib/1.0-SNAPSHOT/maven-metadata.xml.asc";
    requestFor(path);
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), any(StoragePath.class)))
        .thenReturn(new MutablePair<>(null, ArtifactVersionType.SNAPSHOT));
    storageReportsUsage(ARMORED_SIGNATURE.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(ARMORED_SIGNATURE.getBytes(UTF_8)));

    upload(ARMORED_SIGNATURE);

    final var storagePath = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.artifactService)
        .getDeployAndVersionTypesByMetadataTypeFiles(
            any(), eq(ARMORED_SIGNATURE.getBytes(UTF_8)), storagePath.capture());
    assertThat(storagePath.getValue().getRelativePath().getPath()).isEqualTo(path);
    verify(this.artifactService, never()).getDeployAndVersionType(any(), any());
    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.stored).singleElement().isEqualTo(ARMORED_SIGNATURE.getBytes(UTF_8));
  }

  @Test
  @DisplayName("stores nothing and reports no usage for a metadata signature whose kind is refused")
  void storesNothingForAMetadataSignatureWhoseKindIsRefused() throws Exception {
    requestFor("com/example/lib/1.0-SNAPSHOT/maven-metadata.xml.asc");
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), any(StoragePath.class)))
        .thenReturn(new MutablePair<>(null, ArtifactVersionType.SNAPSHOT));
    doThrow(new AccessNotAllowedException("snapshotVersionsAreProhibited"))
        .when(this.artifactService)
        .checkDeploymentRules(any(), any(), any());

    assertThatThrownBy(() -> upload(ARMORED_SIGNATURE))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
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

  private static String filler(final long bytes) {
    return "a".repeat(Math.toIntExact(bytes));
  }

  /** A valid POM padded with a comment so its total size can be pinned exactly (RPS-1121). */
  private static String pomOfSize(final long totalBytes) {
    final var prefix =
        "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>lib</artifactId>\n"
            + "  <version>1.0</version>\n"
            + "  <!-- ";
    final var suffix = " -->\n</project>\n";
    final var padding = totalBytes - prefix.getBytes(UTF_8).length - suffix.getBytes(UTF_8).length;

    return prefix + filler(padding) + suffix;
  }

  @Test
  @DisplayName("stores a maven-metadata.xml exactly at the size limit (RPS-1121)")
  void storesMetadataAtTheSizeLimit() throws Exception {
    final var path = "com/example/lib/maven-metadata.xml";
    final var body = filler(MavenUploadLimits.MAX_METADATA_BYTES);
    requestFor(path);
    when(this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            any(), any(byte[].class), any(StoragePath.class)))
        .thenReturn(new MutablePair<>(ArtifactDeployType.NEW, ArtifactVersionType.RELEASE));
    storageReportsUsage(body.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(body.getBytes(UTF_8)));

    upload(body);

    assertThat(this.stored).singleElement().isEqualTo(body.getBytes(UTF_8));
  }

  @Test
  @DisplayName(
      "refuses a maven-metadata.xml one byte over the size limit, storing nothing (RPS-1121)")
  void rejectsMetadataOverTheSizeLimit() throws Exception {
    final var path = "com/example/lib/maven-metadata.xml";
    final var body = filler(MavenUploadLimits.MAX_METADATA_BYTES + 1);
    requestFor(path);

    assertThatThrownBy(() -> upload(body))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("mavenMetadataTooLarge");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never())
        .getDeployAndVersionTypesByMetadataTypeFiles(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("verifies and stores a POM signature exactly at the size limit (RPS-1121)")
  void verifiesAndStoresAPomSignatureAtTheSizeLimit() throws Exception {
    final var signature = filler(MavenUploadLimits.MAX_POM_SIGNATURE_BYTES);
    requestFor(POM_PATH + ".asc");
    deployIsAllowed();
    storageReportsUsage(signature.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(signature.getBytes(UTF_8)));

    upload(signature);

    assertThat(this.stored).singleElement().isEqualTo(signature.getBytes(UTF_8));
    verify(this.artifactService)
        .verifySignature(any(), any(StoragePath.class), any(Resource.class));
  }

  @Test
  @DisplayName(
      "refuses a POM signature one byte over the size limit before verifying or storing it"
          + " (RPS-1121)")
  void rejectsAPomSignatureOverTheSizeLimit() {
    final var signature = filler(MavenUploadLimits.MAX_POM_SIGNATURE_BYTES + 1);
    requestFor(POM_PATH + ".asc");
    deployIsAllowed();

    assertThatThrownBy(() -> upload(signature))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("mavenSignatureTooLarge");

    verifyNoInteractions(this.storageService);
    verify(this.artifactService, never()).verifySignature(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("stores a POM exactly at the size limit (RPS-1121)")
  void storesAPomAtTheSizeLimit() throws Exception {
    final var pom = pomOfSize(MavenUploadLimits.MAX_POM_BYTES);
    requestFor(POM_PATH);
    deployIsAllowed();
    storageReportsUsage(pom.length());
    when(this.storageService.getResource(anyString(), any(StoragePath.class)))
        .thenReturn(new ByteArrayResource(pom.getBytes(UTF_8)));

    upload(pom);

    assertThat(this.stored).singleElement().isEqualTo(pom.getBytes(UTF_8));
  }

  @Test
  @DisplayName("refuses a POM one byte over the size limit before parsing or storing it (RPS-1121)")
  void rejectsAPomOverTheSizeLimit() {
    final var pom = pomOfSize(MavenUploadLimits.MAX_POM_BYTES + 1);
    requestFor(POM_PATH);
    deployIsAllowed();

    assertThatThrownBy(() -> upload(pom))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pomFileTooLarge");

    verify(this.storageService, never()).writeInputStreamToPath(any(), any(), anyString());
    verify(this.artifactService, never()).createOrUpdateArtifact(any(), any(), any());
    assertThat(this.context.<BaseUsages>getProperty("usages")).isNull();
  }
}
