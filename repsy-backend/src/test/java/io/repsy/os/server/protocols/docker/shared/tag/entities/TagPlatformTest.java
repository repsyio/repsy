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

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TagPlatformTest extends AbstractEntityIdentityTest<TagPlatform> {

  @Override
  protected TagPlatform newEntity(final UUID id) {
    final var tagPlatform = new TagPlatform();
    tagPlatform.setId(id);
    tagPlatform.setPlatform("linux/amd64");
    return tagPlatform;
  }

  @Override
  protected void changeState(final TagPlatform tagPlatform) {
    tagPlatform.setVersion(7);
    tagPlatform.setPlatform("linux/arm64");
    tagPlatform.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected TagPlatform newProxy(final UUID id) {
    return new TagPlatform() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final TagPlatform tagPlatform, final UUID id) {
    tagPlatform.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a tag platform never touch its lazy manifests or tag")
  void hashCodeAndToStringSkipAssociations() {
    final var tagPlatform = this.newEntity(UUID.randomUUID());
    tagPlatform.setManifests(new UntouchableSet<>());

    assertThat(tagPlatform.hashCode()).isEqualTo(TagPlatform.class.hashCode());
    assertThat(tagPlatform.toString()).doesNotContain("manifests=", "tag=");
  }
}
