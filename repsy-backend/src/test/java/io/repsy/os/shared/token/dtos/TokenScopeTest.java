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
package io.repsy.os.shared.token.dtos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@DisplayName("TokenScope")
class TokenScopeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("the declaration order is the alphabetical order of the wire values")
  void declarationOrderIsCanonical() {
    final var values = Arrays.stream(TokenScope.values()).map(TokenScope::getValue).toList();

    assertThat(values).isSorted();
    assertThat(values)
        .containsExactly("profile:read", "repo:manage", "repo:read", "repo:write", "scan:read");
  }

  @Test
  @DisplayName("the AccessTokenScope schema of the spec lists exactly these values, in this order")
  void specEnumIsTheJavaEnum() throws IOException {
    final var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    final Map<String, Object> doc;

    try (var in = new ClassPathResource("openapi/openapi-spec.yaml").getInputStream()) {
      doc = yaml.load(in);
    }

    @SuppressWarnings("unchecked")
    final var schemas =
        (Map<String, Map<String, Object>>) ((Map<?, ?>) doc.get("components")).get("schemas");

    assertThat(schemas.get("AccessTokenScope").get("enum"))
        .isEqualTo(Arrays.stream(TokenScope.values()).map(TokenScope::getValue).toList());
  }

  @Test
  @DisplayName("a scope is found by its wire value, exactly: no case folding, no trimming")
  void fromValueIsExact() {
    assertThat(TokenScope.fromValue("repo:read")).contains(TokenScope.REPO_READ);
    assertThat(TokenScope.fromValue("REPO:READ")).isEmpty();
    assertThat(TokenScope.fromValue(" repo:read")).isEmpty();
    assertThat(TokenScope.fromValue("REPO_READ")).isEmpty();
    assertThat(TokenScope.fromValue("")).isEmpty();
    assertThat(TokenScope.fromValue(null)).isEmpty();
  }

  @Test
  @DisplayName("JSON writes the wire value and reads it back; a value it does not know is refused")
  void jsonUsesTheWireValue() throws IOException {
    assertThat(
            MAPPER.writeValueAsString(EnumSet.of(TokenScope.REPO_WRITE, TokenScope.PROFILE_READ)))
        .isEqualTo("[\"profile:read\",\"repo:write\"]");
    assertThat(MAPPER.readValue("\"scan:read\"", TokenScope.class)).isEqualTo(TokenScope.SCAN_READ);

    assertThatThrownBy(() -> MAPPER.readValue("\"repo:admin\"", TokenScope.class))
        .isInstanceOf(JsonMappingException.class);
    assertThatThrownBy(() -> MAPPER.readValue("\"REPO:READ\"", TokenScope.class))
        .isInstanceOf(JsonMappingException.class);
  }

  @Test
  @DisplayName("every token holds profile:read, whatever it asked for")
  void profileReadIsImplicit() {
    assertThat(TokenScope.withImplicit(List.of())).containsExactly(TokenScope.PROFILE_READ);
    assertThat(TokenScope.withImplicit(List.of(TokenScope.REPO_WRITE)))
        .containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_WRITE);
    assertThat(TokenScope.withImplicit(List.of(TokenScope.PROFILE_READ, TokenScope.PROFILE_READ)))
        .containsExactly(TokenScope.PROFILE_READ);
  }

  @Test
  @DisplayName("the implicit scope comes back in canonical order, whichever order was asked for")
  void withImplicitIsCanonical() {
    assertThat(
            TokenScope.withImplicit(
                List.of(TokenScope.SCAN_READ, TokenScope.REPO_READ, TokenScope.REPO_MANAGE)))
        .containsExactly(
            TokenScope.PROFILE_READ,
            TokenScope.REPO_MANAGE,
            TokenScope.REPO_READ,
            TokenScope.SCAN_READ);
  }

  static Stream<Arguments> grants() {
    // scope, then whether it grants READ, WRITE, MANAGE, NONE
    return Stream.of(
        Arguments.of(TokenScope.PROFILE_READ, false, false, false, true),
        Arguments.of(TokenScope.SCAN_READ, false, false, false, true),
        Arguments.of(TokenScope.REPO_READ, true, false, false, true),
        Arguments.of(TokenScope.REPO_WRITE, true, true, false, true),
        Arguments.of(TokenScope.REPO_MANAGE, true, true, true, true));
  }

  @ParameterizedTest(name = "{0}: read={1} write={2} manage={3} none={4}")
  @MethodSource("grants")
  @DisplayName("a manage scope grants write and read, a write scope grants read, none grants more")
  void grantsMatrix(
      final TokenScope scope,
      final boolean read,
      final boolean write,
      final boolean manage,
      final boolean none) {
    assertThat(scope.grants(Permission.READ)).as("READ").isEqualTo(read);
    assertThat(scope.grants(Permission.WRITE)).as("WRITE").isEqualTo(write);
    assertThat(scope.grants(Permission.MANAGE)).as("MANAGE").isEqualTo(manage);
    assertThat(scope.grants(Permission.NONE)).as("NONE").isEqualTo(none);
  }

  @Test
  @DisplayName("MANAGE is never implied: no scope but repo:manage grants it, however many are held")
  void manageIsNeverImplied() {
    final Collection<TokenScope> everythingButManage =
        EnumSet.complementOf(EnumSet.of(TokenScope.REPO_MANAGE));

    assertThat(TokenScope.permits(everythingButManage, Permission.MANAGE)).isFalse();
    assertThat(TokenScope.permits(EnumSet.of(TokenScope.REPO_MANAGE), Permission.MANAGE)).isTrue();
  }

  @Test
  @DisplayName("a set of scopes permits what any one of them grants")
  void permitsIsTheUnion() {
    final Set<TokenScope> readAndScan = EnumSet.of(TokenScope.REPO_READ, TokenScope.SCAN_READ);

    assertThat(TokenScope.permits(readAndScan, Permission.READ)).isTrue();
    assertThat(TokenScope.permits(readAndScan, Permission.WRITE)).isFalse();
    assertThat(TokenScope.permits(EnumSet.of(TokenScope.REPO_WRITE), Permission.READ)).isTrue();
    assertThat(TokenScope.permits(EnumSet.of(TokenScope.REPO_WRITE), Permission.WRITE)).isTrue();
    assertThat(TokenScope.permits(EnumSet.noneOf(TokenScope.class), Permission.READ)).isFalse();
    assertThat(TokenScope.permits(EnumSet.noneOf(TokenScope.class), Permission.NONE)).isTrue();
  }
}
