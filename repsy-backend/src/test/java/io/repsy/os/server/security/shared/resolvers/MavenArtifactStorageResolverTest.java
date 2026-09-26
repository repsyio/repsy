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
package io.repsy.os.server.security.shared.resolvers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/**
 * The stored snapshot metadata is only a hint for finding the build a scan is about. A corrupt one
 * must not surface as the {@code 400} that {@code ArtifactUtils.readMetadata} answers an upload
 * with (RPS-1180), and a snapshot that has no usable metadata (sbt, Ivy) resolves to the newest
 * main file stored in its version directory (RPS-1420). So does a snapshot whose metadata names a
 * build that is not stored (RPS-1447).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MavenArtifactStorageResolver snapshots (RPS-1180, RPS-1420, RPS-1447)")
class MavenArtifactStorageResolverTest {

  private static final String METADATA_PATH = "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
  private static final String SNAPSHOT_METADATA =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      </versioning></metadata>""";

  private static final String VERSION_DIRECTORY = "com/acme/lib/1.0-SNAPSHOT/";
  private static final String LITERAL_JAR_PATH = VERSION_DIRECTORY + "lib-1.0-SNAPSHOT.jar";

  private final UUID repoId = UUID.randomUUID();

  @Mock ArtifactRepository artifactRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock StorageStrategy storageStrategy;

  private static StoragePath pathOf(final String relativePath) {
    return argThat(path -> path != null && path.getRelativePath().getPath().equals(relativePath));
  }

  private MavenArtifactStorageResolver resolver() {
    return new MavenArtifactStorageResolver(
        this.artifactRepository, this.artifactVersionRepository, this.storageStrategy);
  }

  private void stubVersionDir(final String... names) {
    when(this.storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenReturn(
            Arrays.stream(names)
                .map(name -> StorageItemInfo.builder().name(name).directory(false).build())
                .toList());
  }

  /** A stubbed storage answers a call with other arguments than stubbed as a mismatch: say it. */
  private void stubNoMetadata() {
    when(this.storageStrategy.get(pathOf(METADATA_PATH), eq("mvn"))).thenReturn(Optional.empty());
  }

  /** A path built by the GAV calculator starts with a slash, one found by listing does not. */
  private void stubNotStored(final String path) {
    when(this.storageStrategy.get(pathOf("/" + path), eq("mvn"))).thenReturn(Optional.empty());
  }

  private void stubStored(final String path, final String body) {
    when(this.storageStrategy.get(pathOf(path), eq("mvn")))
        .thenReturn(Optional.of(new ByteArrayResource(body.getBytes(UTF_8))));
  }

  @Test
  @DisplayName("resolves the timestamped build of a snapshot from its metadata")
  void resolvesTheBuildFromValidMetadata() {
    when(this.storageStrategy.get(any(StoragePath.class), eq("mvn")))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new ByteArrayResource(
                        (invocation
                                    .<StoragePath>getArgument(0)
                                    .getRelativePath()
                                    .getPath()
                                    .endsWith("maven-metadata.xml")
                                ? SNAPSHOT_METADATA
                                : "jar")
                            .getBytes(UTF_8))));

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValueSatisfying(path -> assertThat(path).endsWith("lib-1.0-20260921.101010-1.jar"));
  }

  @Test
  @DisplayName("corrupt stored snapshot metadata does not throw, it falls back to the stored jar")
  void corruptMetadataFallsBackToTheStoredJar() {
    this.stubStored(METADATA_PATH, "<metadata><versioning><snapshot>");
    this.stubVersionDir("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom");
    this.stubStored(LITERAL_JAR_PATH, "jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(LITERAL_JAR_PATH);
  }

  @Test
  @DisplayName("corrupt stored snapshot metadata and no stored jar resolves to nothing (RPS-1180)")
  void corruptMetadataWithoutAJarResolvesToNothing() {
    this.stubStored(METADATA_PATH, "<metadata><versioning><snapshot>");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .isEmpty();
    verify(this.storageStrategy, never())
        .get(
            argThat(path -> path != null && path.getRelativePath().getPath().endsWith(".jar")),
            any());
  }

  @Test
  @DisplayName("a snapshot without any metadata resolves to its literal jar (RPS-1420)")
  void noMetadataResolvesTheLiteralJar() {
    this.stubNoMetadata();
    this.stubVersionDir("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom");
    this.stubStored(LITERAL_JAR_PATH, "jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(LITERAL_JAR_PATH);
  }

  @Test
  @DisplayName("without metadata the newest timestamped jar wins over the literal one (RPS-1420)")
  void noMetadataResolvesTheNewestBuild() {
    this.stubNoMetadata();
    this.stubVersionDir(
        "lib-1.0-SNAPSHOT.jar",
        "lib-1.0-20260921.101010-9.jar",
        "lib-1.0-20260921.101010-10.jar",
        "lib-1.0-20260920.101010-99.jar");
    this.stubStored(VERSION_DIRECTORY + "lib-1.0-20260921.101010-10.jar", "jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(VERSION_DIRECTORY + "lib-1.0-20260921.101010-10.jar");
  }

  @Test
  @DisplayName("classifier jars, checksums and signatures are not the main jar (RPS-1420)")
  void noMetadataIgnoresClassifierJars() {
    this.stubVersionDir(
        "lib-1.0-SNAPSHOT-sources.jar",
        "lib-1.0-SNAPSHOT-javadoc.jar",
        "lib-1.0-SNAPSHOT.jar.sha1",
        "lib-1.0-SNAPSHOT.jar.asc",
        "other-1.0-SNAPSHOT.jar",
        "lib-1.0-SNAPSHOT.pom");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .isEmpty();
  }

  @Test
  @DisplayName("a version directory that is gone resolves to nothing (RPS-1420)")
  void noMetadataInAMissingDirectory() {
    when(this.storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .isEmpty();
  }

  @Test
  @DisplayName("metadata that names no build falls back to the stored jar (RPS-1420)")
  void metadataWithoutABuildFallsBack() {
    this.stubStored(
        METADATA_PATH,
        "<metadata><groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<versioning><lastUpdated>20260921101010</lastUpdated></versioning></metadata>");
    this.stubVersionDir("lib-1.0-SNAPSHOT.jar");
    this.stubStored(LITERAL_JAR_PATH, "jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(LITERAL_JAR_PATH);
  }

  @Test
  @DisplayName("the packaging of the version decides the extension of the fallback (RPS-1420)")
  void noMetadataFollowsThePackaging() {
    this.stubNoMetadata();
    final var artifact = new Artifact();
    artifact.setId(UUID.randomUUID());
    final var version = new ArtifactVersion();
    version.setPackaging("war");
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(
            artifact.getId(), "1.0-SNAPSHOT"))
        .thenReturn(Optional.of(version));
    this.stubVersionDir("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.war");
    this.stubStored(VERSION_DIRECTORY + "lib-1.0-SNAPSHOT.war", "war");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(VERSION_DIRECTORY + "lib-1.0-SNAPSHOT.war");
  }

  @Test
  @DisplayName("a build named by the metadata but not stored falls back to the newest stored jar")
  void namedBuildNotStoredFallsBack() {
    final var missing = VERSION_DIRECTORY + "lib-1.0-20260921.101010-1.jar";
    final var stored = VERSION_DIRECTORY + "lib-1.0-20260920.101010-7.jar";
    this.stubStored(METADATA_PATH, SNAPSHOT_METADATA);
    this.stubNotStored(missing);
    this.stubVersionDir(
        "lib-1.0-20260920.101010-6.jar", "lib-1.0-20260920.101010-7.jar", "lib-1.0-SNAPSHOT.pom");
    this.stubStored(stored, "jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue(stored);
  }

  @Test
  @DisplayName("a build named by the metadata that is stored is not replaced by a newer listing")
  void namedBuildStoredWins() {
    final var named = VERSION_DIRECTORY + "lib-1.0-20260921.101010-1.jar";
    this.stubStored(METADATA_PATH, SNAPSHOT_METADATA);
    this.stubStored("/" + named, "jar");

    // The M2 GAV calculator yields a leading slash on the path it builds.
    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .hasValue("/" + named);
    verify(this.storageStrategy, never()).listDirectoryContents(any(StoragePath.class));
  }

  @Test
  @DisplayName("a build named by the metadata and no stored jar at all resolves to nothing")
  void namedBuildNotStoredAndNoJar() {
    this.stubStored(METADATA_PATH, SNAPSHOT_METADATA);
    this.stubNotStored(VERSION_DIRECTORY + "lib-1.0-20260921.101010-1.jar");
    this.stubVersionDir("lib-1.0-SNAPSHOT.pom", "lib-1.0-SNAPSHOT-sources.jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .isEmpty();
  }

  @Test
  @DisplayName("a release that is not stored does not fall back to another file")
  void missingReleaseDoesNotFallBack() {
    this.stubNotStored("com/acme/lib/1.0/lib-1.0.jar");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0")).isEmpty();
    verify(this.storageStrategy, never()).listDirectoryContents(any(StoragePath.class));
  }
}
