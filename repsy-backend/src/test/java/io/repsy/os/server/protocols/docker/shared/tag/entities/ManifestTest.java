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
package io.repsy.os.server.protocols.docker.shared.tag.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.docker.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManifestTest extends AbstractEntityIdentityTest<Manifest> {

  @Override
  protected Manifest newEntity(final UUID id) {
    final var manifest = new Manifest();
    manifest.setId(id);
    manifest.setName("latest");
    manifest.setDigest("sha256:aaaa");
    return manifest;
  }

  @Override
  protected void changeState(final Manifest manifest) {
    manifest.setVersion(7);
    manifest.setName("renamed");
    manifest.setDigest("sha256:bbbb");
    manifest.setConfigDigest("sha256:cccc");
    manifest.setConfigSize(42);
    manifest.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected Manifest newProxy(final UUID id) {
    return new Manifest() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final Manifest manifest, final UUID id) {
    manifest.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a manifest never touch its layers or tag platform")
  void hashCodeAndToStringSkipAssociations() {
    final var manifest = this.newEntity(UUID.randomUUID());
    manifest.setLayers(new UntouchableSet<>());

    assertThat(manifest.hashCode()).isEqualTo(Manifest.class.hashCode());
    assertThat(manifest.toString()).doesNotContain("layers=", "tagPlatform=");
  }
}
