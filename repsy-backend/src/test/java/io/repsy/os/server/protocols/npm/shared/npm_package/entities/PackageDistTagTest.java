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

class PackageDistTagTest extends AbstractEntityIdentityTest<PackageDistTag> {

  @Override
  protected PackageDistTag newEntity(final UUID id) {
    final var packageDistTag = new PackageDistTag();
    packageDistTag.setId(id);
    packageDistTag.setTagName("latest");
    return packageDistTag;
  }

  @Override
  protected void changeState(final PackageDistTag packageDistTag) {
    packageDistTag.setTagName("next");
    packageDistTag.setCreatedAt(Instant.now());
  }

  @Override
  protected PackageDistTag newProxy(final UUID id) {
    return new PackageDistTag() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final PackageDistTag packageDistTag, final UUID id) {
    packageDistTag.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a package dist tag never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var packageDistTag = this.newEntity(UUID.randomUUID());

    assertThat(packageDistTag.hashCode()).isEqualTo(PackageDistTag.class.hashCode());
    assertThat(packageDistTag.toString()).doesNotContain("packageVersion=");
  }
}
