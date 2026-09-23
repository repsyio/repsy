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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionDeveloperTest extends AbstractEntityIdentityTest<VersionDeveloper> {

  @Override
  protected VersionDeveloper newEntity(final UUID id) {
    final var versionDeveloper = new VersionDeveloper();
    versionDeveloper.setId(id);
    versionDeveloper.setName("Jane");
    return versionDeveloper;
  }

  @Override
  protected void changeState(final VersionDeveloper versionDeveloper) {
    versionDeveloper.setName("John");
    versionDeveloper.setEmail("john@example.org");
  }

  @Override
  protected VersionDeveloper newProxy(final UUID id) {
    return new VersionDeveloper() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final VersionDeveloper versionDeveloper, final UUID id) {
    versionDeveloper.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a version developer never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var versionDeveloper = this.newEntity(UUID.randomUUID());

    assertThat(versionDeveloper.hashCode()).isEqualTo(VersionDeveloper.class.hashCode());
    assertThat(versionDeveloper.toString()).doesNotContain("artifactVersion=");
  }
}
