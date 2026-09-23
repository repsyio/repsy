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
package io.repsy.os.server.protocols.nuget.shared.packages.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NuGetPackageVersionTest extends AbstractEntityIdentityTest<NuGetPackageVersion> {

  @Override
  protected NuGetPackageVersion newEntity(final UUID id) {
    final var nuGetPackageVersion = new NuGetPackageVersion();
    nuGetPackageVersion.setId(id);
    nuGetPackageVersion.setVersion("1.0.0");
    return nuGetPackageVersion;
  }

  @Override
  protected void changeState(final NuGetPackageVersion nuGetPackageVersion) {
    nuGetPackageVersion.setVersion("2.0.0");
    nuGetPackageVersion.setDownloadCount(42L);
    nuGetPackageVersion.setPublishedAt(Instant.now());
  }

  @Override
  protected NuGetPackageVersion newProxy(final UUID id) {
    return new NuGetPackageVersion() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final NuGetPackageVersion nuGetPackageVersion, final UUID id) {
    nuGetPackageVersion.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a NuGet package version never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var nuGetPackageVersion = this.newEntity(UUID.randomUUID());

    assertThat(nuGetPackageVersion.hashCode()).isEqualTo(NuGetPackageVersion.class.hashCode());
    assertThat(nuGetPackageVersion.toString()).doesNotContain("nugetPackage=");
  }
}
