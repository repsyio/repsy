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
package io.repsy.protocols.golang.shared.module.validators;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GoModFileValidator")
class GoModFileValidatorTest {

  private static final String EXPECTED_PATH = "example.com/demo";

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("rejects an empty go.mod")
  void rejectsEmptyContent() {
    assertThatThrownBy(() -> GoModFileValidator.validate(new byte[0], EXPECTED_PATH))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModFileEmpty");
  }

  @Test
  @DisplayName("rejects a go.mod without a module directive")
  void rejectsMissingModuleDirective() {
    assertThatThrownBy(() -> GoModFileValidator.validate(bytes("go 1.22\n"), EXPECTED_PATH))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModMissingModuleDirective");
  }

  private static Stream<Arguments> matchingDirectivesAndPaths() {
    return Stream.of(
        Arguments.of("module github.com/acme/tool\n\ngo 1.22\n", "github.com/acme/tool"),
        Arguments.of("module example.com\n", "example.com"),
        Arguments.of("go 1.22\nmodule gopkg.in/yaml.v3\n", "gopkg.in/yaml.v3"));
  }

  @ParameterizedTest
  @MethodSource("matchingDirectivesAndPaths")
  @DisplayName("accepts a module path whose first segment is a domain name and matches the URL")
  void acceptsDomainModulePath(final String goMod, final String expectedPath) {
    assertThatCode(() -> GoModFileValidator.validate(bytes(goMod), expectedPath))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"module acme/tool\n", "module tool\n", "module /tool\n"})
  @DisplayName("rejects a module path whose first segment has no dot")
  void rejectsModulePathWithoutDomain(final String goMod) {
    assertThatThrownBy(() -> GoModFileValidator.validate(bytes(goMod), EXPECTED_PATH))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModInvalidModulePath");
  }

  @Test
  @DisplayName(
      "rejects a go.mod whose module directive names a different module than the URL path"
          + " (RPS-1228)")
  void rejectsMismatchedModulePath() {
    final var goMod = "module example.com/other\n\ngo 1.22\n";

    assertThatThrownBy(() -> GoModFileValidator.validate(bytes(goMod), EXPECTED_PATH))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModModulePathMismatch");
  }

  @Test
  @DisplayName("accepts a quoted module directive that matches the URL path once unquoted")
  void acceptsQuotedModuleDirective() {
    final var goMod = "module \"example.com/demo\"\n\ngo 1.22\n";

    assertThatCode(() -> GoModFileValidator.validate(bytes(goMod), EXPECTED_PATH))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("accepts a v2+ module directive that carries the same /vN suffix as the URL path")
  void acceptsMatchingMajorVersionSuffix() {
    final var goMod = "module example.com/mod/v2\n\ngo 1.22\n";

    assertThatCode(() -> GoModFileValidator.validate(bytes(goMod), "example.com/mod/v2"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rejects a module directive that differs from the URL path only by case")
  void rejectsCaseOnlyMismatch() {
    final var goMod = "module example.com/Foo\n\ngo 1.22\n";

    assertThatThrownBy(() -> GoModFileValidator.validate(bytes(goMod), "example.com/foo"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModModulePathMismatch");
  }
}
