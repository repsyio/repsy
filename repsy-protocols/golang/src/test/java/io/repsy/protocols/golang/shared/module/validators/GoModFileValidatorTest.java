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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GoModFileValidator")
class GoModFileValidatorTest {

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("rejects an empty go.mod")
  void rejectsEmptyContent() {
    assertThatThrownBy(() -> GoModFileValidator.validate(new byte[0]))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModFileEmpty");
  }

  @Test
  @DisplayName("rejects a go.mod without a module directive")
  void rejectsMissingModuleDirective() {
    assertThatThrownBy(() -> GoModFileValidator.validate(bytes("go 1.22\n")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModMissingModuleDirective");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "module github.com/acme/tool\n\ngo 1.22\n",
        "module example.com\n",
        "go 1.22\nmodule gopkg.in/yaml.v3\n"
      })
  @DisplayName("accepts a module path whose first segment is a domain name")
  void acceptsDomainModulePath(final String goMod) {
    assertThatCode(() -> GoModFileValidator.validate(bytes(goMod))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"module acme/tool\n", "module tool\n", "module /tool\n"})
  @DisplayName("rejects a module path whose first segment has no dot")
  void rejectsModulePathWithoutDomain(final String goMod) {
    assertThatThrownBy(() -> GoModFileValidator.validate(bytes(goMod)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModInvalidModulePath");
  }
}
