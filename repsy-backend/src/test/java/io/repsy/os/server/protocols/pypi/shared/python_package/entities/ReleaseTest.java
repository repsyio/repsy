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
package io.repsy.os.server.protocols.pypi.shared.python_package.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseTest extends AbstractEntityIdentityTest<Release> {

  @Override
  protected Release newEntity(final UUID id) {
    final var release = new Release();
    release.setId(id);
    release.setVersion("1.0");
    return release;
  }

  @Override
  protected void changeState(final Release release) {
    release.setVersion("2.0");
    release.setFinalRelease(true);
    release.setCreatedAt(Instant.now());
  }

  @Override
  protected Release newProxy(final UUID id) {
    return new Release() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final Release release, final UUID id) {
    release.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a release never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var release = this.newEntity(UUID.randomUUID());
    release.setReleaseClassifiers(new UntouchableSet<>());
    release.setReleaseProjectURLS(new UntouchableSet<>());

    assertThat(release.hashCode()).isEqualTo(Release.class.hashCode());
    assertThat(release.toString())
        .doesNotContain("pypiPackage=", "releaseClassifiers=", "releaseProjectURLS=");
  }
}
