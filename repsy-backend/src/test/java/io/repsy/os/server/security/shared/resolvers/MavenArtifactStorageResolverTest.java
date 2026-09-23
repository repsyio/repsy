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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
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
 * must fall back to "not resolved" and not surface as the {@code 400} that {@code
 * ArtifactUtils.readMetadata} answers an upload with (RPS-1180).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MavenArtifactStorageResolver snapshot metadata (RPS-1180)")
class MavenArtifactStorageResolverTest {

  private static final String METADATA_PATH = "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
  private static final String SNAPSHOT_METADATA =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      </versioning></metadata>""";

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
  @DisplayName("corrupt stored snapshot metadata resolves to nothing instead of throwing")
  void corruptMetadataResolvesToNothing() {
    this.stubStored(METADATA_PATH, "<metadata><versioning><snapshot>");

    assertThat(this.resolver().resolve(this.repoId, "mvn", "com.acme:lib", "1.0-SNAPSHOT"))
        .isEmpty();
    verify(this.storageStrategy, never())
        .get(
            argThat(path -> path != null && path.getRelativePath().getPath().endsWith(".jar")),
            any());
  }
}
