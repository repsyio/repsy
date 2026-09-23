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
package io.repsy.os.server.protocols.maven.shared.artifact.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArtifactVersionTest extends AbstractEntityIdentityTest<ArtifactVersion> {

  @Override
  protected ArtifactVersion newEntity(final UUID id) {
    final var artifactVersion = new ArtifactVersion();
    artifactVersion.setId(id);
    artifactVersion.setVersionName("1.0");
    return artifactVersion;
  }

  @Override
  protected void changeState(final ArtifactVersion artifactVersion) {
    artifactVersion.setVersionName("2.0");
    artifactVersion.setSigned(true);
    artifactVersion.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected ArtifactVersion newProxy(final UUID id) {
    return new ArtifactVersion() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final ArtifactVersion artifactVersion, final UUID id) {
    artifactVersion.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a artifact version never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var artifactVersion = this.newEntity(UUID.randomUUID());
    artifactVersion.setVersionLicenses(new UntouchableSet<>());
    artifactVersion.setVersionDevelopers(new UntouchableSet<>());

    assertThat(artifactVersion.hashCode()).isEqualTo(ArtifactVersion.class.hashCode());
    assertThat(artifactVersion.toString())
        .doesNotContain("artifact=", "versionLicenses=", "versionDevelopers=");
  }
}
