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
package io.repsy.protocols.pypi.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PypiPackageUtils.parseUploadForm")
class PypiPackageUtilsTest {

  @Test
  @DisplayName("binds twine's snake_case form fields onto the form")
  void bindsSnakeCaseFields() {
    final var form =
        PypiPackageUtils.parseUploadForm(
            Map.of(
                "name", "pkg",
                "metadata_version", "2.1",
                "home_page", "https://example.test",
                "sha256_digest", "abc",
                "requires_python", ">=3.9",
                "project_urls", new String[] {"Homepage,https://example.test"}));

    assertThat(form.getMetadataVersion()).isEqualTo("2.1");
    assertThat(form.getHomePage()).isEqualTo("https://example.test");
    assertThat(form.getSha256Digest()).isEqualTo("abc");
    assertThat(form.getRequiresPython()).isEqualTo(">=3.9");
    assertThat(form.getProjectUrls()).containsExactly("Homepage,https://example.test");
  }
}
