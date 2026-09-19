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
package io.repsy.protocols.ruby.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractRubyStorageService")
class AbstractRubyStorageServiceTest {

  @Test
  @DisplayName("buildFilename() omits the platform for the default ruby platform")
  void omitsDefaultPlatform() {
    assertThat(AbstractRubyStorageService.buildFilename("rack", "2.2.8", "ruby"))
        .isEqualTo("rack-2.2.8.gem");
  }

  @Test
  @DisplayName("buildFilename() appends a non-default platform")
  void appendsNonDefaultPlatform() {
    assertThat(AbstractRubyStorageService.buildFilename("nokogiri", "1.16.0", "x86_64-linux"))
        .isEqualTo("nokogiri-1.16.0-x86_64-linux.gem");
  }
}
