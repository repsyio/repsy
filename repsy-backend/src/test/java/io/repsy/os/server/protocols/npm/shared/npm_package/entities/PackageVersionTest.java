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
package io.repsy.os.server.protocols.npm.shared.npm_package.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackageVersionTest extends AbstractEntityIdentityTest<PackageVersion> {

  @Override
  protected PackageVersion newEntity(final UUID id) {
    final var packageVersion = new PackageVersion();
    packageVersion.setId(id);
    packageVersion.setVersion("1.0.0");
    return packageVersion;
  }

  @Override
  protected void changeState(final PackageVersion packageVersion) {
    packageVersion.setVersion("2.0.0");
    packageVersion.setDeprecated(true);
    packageVersion.setCreatedAt(Instant.now());
  }

  @Override
  protected PackageVersion newProxy(final UUID id) {
    return new PackageVersion() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final PackageVersion packageVersion, final UUID id) {
    packageVersion.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a package version never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var packageVersion = this.newEntity(UUID.randomUUID());
    packageVersion.setPackageKeywords(new UntouchableSet<>());
    packageVersion.setPackageDistTags(new UntouchableSet<>());
    packageVersion.setPackageMaintainers(new UntouchableSet<>());

    assertThat(packageVersion.hashCode()).isEqualTo(PackageVersion.class.hashCode());
    assertThat(packageVersion.toString())
        .doesNotContain(
            "npmPackage=", "packageKeywords=", "packageDistTags=", "packageMaintainers=");
  }
}
