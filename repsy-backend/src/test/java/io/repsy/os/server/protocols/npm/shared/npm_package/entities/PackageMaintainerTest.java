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

class PackageMaintainerTest extends AbstractEntityIdentityTest<PackageMaintainer> {

  @Override
  protected PackageMaintainer newEntity(final UUID id) {
    final var packageMaintainer = new PackageMaintainer();
    packageMaintainer.setId(id);
    packageMaintainer.setName("jane");
    return packageMaintainer;
  }

  @Override
  protected void changeState(final PackageMaintainer packageMaintainer) {
    packageMaintainer.setName("john");
    packageMaintainer.setEmail("john@example.org");
    packageMaintainer.setCreatedAt(Instant.now());
  }

  @Override
  protected PackageMaintainer newProxy(final UUID id) {
    return new PackageMaintainer() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final PackageMaintainer packageMaintainer, final UUID id) {
    packageMaintainer.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a package maintainer never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var packageMaintainer = this.newEntity(UUID.randomUUID());

    assertThat(packageMaintainer.hashCode()).isEqualTo(PackageMaintainer.class.hashCode());
    assertThat(packageMaintainer.toString()).doesNotContain("packageVersion=");
  }
}
