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
package io.repsy.os.shared.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.os.generated.model.RepoCreateRequest;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("RepoType reading (RPS-1269)")
class RepoTypeConversionTest {

  private final JsonMapper json = JsonMapper.builder().build();

  private static DefaultFormattingConversionService conversionService() {
    final var service = new DefaultFormattingConversionService();
    new RepoTypeConversionConfiguration().addFormatters(service);

    return service;
  }

  @ParameterizedTest
  @ValueSource(strings = {"maven", "Maven", "MAVEN", " maven "})
  @DisplayName("a request parameter is read without regard to case")
  void parameterIsCaseInsensitive(final String value) {
    assertThat(conversionService().convert(value, RepoType.class)).isEqualTo(RepoType.MAVEN);
  }

  @Test
  @DisplayName("a blank request parameter is no type, an unknown one fails the conversion")
  void parameterBlankAndUnknown() {
    assertThat(conversionService().convert(" ", RepoType.class)).isNull();
    assertThatThrownBy(() -> conversionService().convert("mvn", RepoType.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the conversion is for RepoType alone: other enums keep the exact-name match")
  void otherEnumParametersStayCaseSensitive() {
    final var service = conversionService();

    assertThat(service.convert("HIGH", Severity.class)).isEqualTo(Severity.HIGH);
    assertThatThrownBy(() -> service.convert("high", Severity.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  @DisplayName("a JSON body names a type in either case, and it is written in upper case")
  void jsonIsCaseInsensitive(final RepoType type) {
    final var lower = "{\"name\":\"a\",\"type\":\"" + type.name().toLowerCase() + "\"}";
    final var upper = "{\"name\":\"a\",\"type\":\"" + type.name() + "\"}";

    assertThat(this.json.readValue(lower, RepoCreateRequest.class).getType()).isEqualTo(type);
    assertThat(this.json.readValue(upper, RepoCreateRequest.class).getType()).isEqualTo(type);
    assertThat(this.json.writeValueAsString(type)).isEqualTo("\"" + type.name() + "\"");
  }

  @Test
  @DisplayName("a JSON body with an unknown type is refused")
  void jsonUnknownType() {
    assertThatThrownBy(
            () -> this.json.readValue("{\"name\":\"a\",\"type\":\"mvn\"}", RepoCreateRequest.class))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
  }

  @Test
  @DisplayName("the generated request DTO uses the hand-written RepoType, not a copy of it")
  void generatedModelUsesTheSharedEnum() throws Exception {
    assertThat(RepoCreateRequest.class.getMethod("getType").getReturnType())
        .isSameAs(RepoType.class);
  }

  @Test
  @DisplayName("JSON of other enums stays case-sensitive")
  void otherEnumsStayCaseSensitive() {
    assertThat(this.json.readValue("\"HIGH\"", Severity.class)).isEqualTo(Severity.HIGH);
    assertThat(this.json.readValue("\"ADMIN\"", UserRole.class)).isEqualTo(UserRole.ADMIN);
    assertThat(this.json.readValue("\"COMPLETED\"", ScanStatus.class))
        .isEqualTo(ScanStatus.COMPLETED);

    assertThatThrownBy(() -> this.json.readValue("\"high\"", Severity.class))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
    assertThatThrownBy(() -> this.json.readValue("\"admin\"", UserRole.class))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
    assertThatThrownBy(() -> this.json.readValue("\"completed\"", ScanStatus.class))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
  }
}
