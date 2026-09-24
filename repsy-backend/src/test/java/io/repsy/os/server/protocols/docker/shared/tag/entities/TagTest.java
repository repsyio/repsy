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

class TagTest extends AbstractEntityIdentityTest<Tag> {

  @Override
  protected Tag newEntity(final UUID id) {
    final var tag = new Tag();
    tag.setId(id);
    tag.setName("latest");
    tag.setDigest("sha256:aaaa");
    return tag;
  }

  @Override
  protected void changeState(final Tag tag) {
    tag.setVersion(7);
    tag.setName("renamed");
    tag.setDigest("sha256:bbbb");
    tag.setPlatform("linux/arm64");
    tag.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected Tag newProxy(final UUID id) {
    return new Tag() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final Tag tag, final UUID id) {
    tag.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a tag never touch its manifest or image")
  void hashCodeAndToStringSkipAssociations() {
    final var tag = this.newEntity(UUID.randomUUID());

    assertThat(tag.hashCode()).isEqualTo(Tag.class.hashCode());
    assertThat(tag.toString()).doesNotContain("manifest=", "image=");
  }
}
