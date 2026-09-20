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
package io.repsy.os.server.protocols.docker.shared.image.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.docker.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageTest extends AbstractEntityIdentityTest<Image> {

  @Override
  protected Image newEntity(final UUID id) {
    final var image = new Image();
    image.setId(id);
    image.setName("library/alpine");
    image.setDigest("sha256:aaaa");
    return image;
  }

  @Override
  protected void changeState(final Image image) {
    image.setName("library/busybox");
    image.setSize(42);
    image.setDigest("sha256:bbbb");
    image.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected Image newProxy(final UUID id) {
    return new Image() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final Image image, final UUID id) {
    image.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing an image never touch its tags or repo")
  void hashCodeAndToStringSkipAssociations() {
    final var image = this.newEntity(UUID.randomUUID());
    image.setTags(new UntouchableSet<>());

    assertThat(image.hashCode()).isEqualTo(Image.class.hashCode());
    assertThat(image.toString()).doesNotContain("tags=", "repo=");
  }
}
