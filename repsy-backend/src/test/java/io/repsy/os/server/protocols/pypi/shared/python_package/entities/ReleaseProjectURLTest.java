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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseProjectURLTest extends AbstractEntityIdentityTest<ReleaseProjectURL> {

  @Override
  protected ReleaseProjectURL newEntity(final UUID id) {
    final var releaseProjectURL = new ReleaseProjectURL();
    releaseProjectURL.setId(id);
    releaseProjectURL.setLabel("Home");
    return releaseProjectURL;
  }

  @Override
  protected void changeState(final ReleaseProjectURL releaseProjectURL) {
    releaseProjectURL.setLabel("Docs");
    releaseProjectURL.setUrl("https://example.org");
  }

  @Override
  protected ReleaseProjectURL newProxy(final UUID id) {
    return new ReleaseProjectURL() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final ReleaseProjectURL releaseProjectURL, final UUID id) {
    releaseProjectURL.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a release project URL never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var releaseProjectURL = this.newEntity(UUID.randomUUID());

    assertThat(releaseProjectURL.hashCode()).isEqualTo(ReleaseProjectURL.class.hashCode());
    assertThat(releaseProjectURL.toString()).doesNotContain("release=");
  }
}
