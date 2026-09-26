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
package io.repsy.protocols.ruby.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RubyContentDisposition (RPS-1442)")
class RubyContentDispositionTest {

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "/gems/demo-1.2.3.gem, 'attachment; filename=\"demo-1.2.3.gem\"'",
    "/gems/nested/demo-1.2.3-java.gem, 'attachment; filename=\"demo-1.2.3-java.gem\"'",
    "/quick/Marshal.4.8/demo-1.2.3.gemspec.rz,"
        + " 'attachment; filename=\"demo-1.2.3.gemspec.rz\"'",
    "/specs.4.8.gz, 'attachment; filename=\"specs.4.8.gz\"'",
    "/latest_specs.4.8.gz, 'attachment; filename=\"latest_specs.4.8.gz\"'",
    "/prerelease_specs.4.8.gz, 'attachment; filename=\"prerelease_specs.4.8.gz\"'",
  })
  @DisplayName("a file is an attachment named after its last path segment")
  void filesAreNamed(final String path, final String expected) {
    assertThat(RubyContentDisposition.forPath(path)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/info/demo", "/info/demo.rb"})
  @DisplayName("the compact index info is inline and names no file, even for a dotted gem name")
  void infoNamesNoFile(final String path) {
    assertThat(RubyContentDisposition.forPath(path)).isEqualTo("inline");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/names", "/versions", "/api/v1/gems", "/gems/demo-1.2.3"})
  @DisplayName("a route with no extension needs no header")
  void noHeader(final String path) {
    assertThat(RubyContentDisposition.forPath(path)).isNull();
  }

  @Test
  @DisplayName("is a utility class")
  void utilityClass() throws Exception {
    final var constructor = RubyContentDisposition.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    assertThat(org.assertj.core.api.Assertions.catchThrowable(constructor::newInstance))
        .hasCauseInstanceOf(UnsupportedOperationException.class);
  }
}
