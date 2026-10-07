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
package io.repsy.os.shared.token.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenHash")
class TokenHashTest {

  private static final String PERSONAL_ACCESS_TOKEN = "rut-[A-Za-z0-9_-]{43}";

  @Test
  @DisplayName("is SHA-256 as lower-case hex: the published test vectors")
  void isSha256() {
    assertThat(TokenHash.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(TokenHash.hash(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("is 64 characters of lower-case hex, which is what the column holds")
  void fitsTheColumn() {
    assertThat(TokenHash.hash("rut-anything")).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("is unsalted and case sensitive: one input, one hash, so a token can be looked up")
  void isDeterministic() {
    assertThat(TokenHash.hash("rut-abc")).isEqualTo(TokenHash.hash("rut-abc"));
    assertThat(TokenHash.hash("rut-abc")).isNotEqualTo(TokenHash.hash("rut-ABC"));
    assertThat(TokenHash.hash("rut-abc")).isNotEqualTo(TokenHash.hash("rut-abc "));
  }

  @Test
  @DisplayName("hashes like DeployTokenHash, the algorithm the other kind of token is stored with")
  void matchesTheDeployTokenHash() {
    assertThat(TokenHash.hash("rut-abc")).isEqualTo(DeployTokenHash.hash("rut-abc"));
  }

  @Test
  @DisplayName("a personal access token has the rut- prefix and 43 URL-safe characters")
  void personalAccessTokenFormat() {
    assertThat(TokenFactory.personalAccessToken()).matches(PERSONAL_ACCESS_TOKEN);
  }

  @Test
  @DisplayName("two personal access tokens differ, and neither is its own hash")
  void personalAccessTokensAreDistinct() {
    final var first = TokenFactory.personalAccessToken();
    final var second = TokenFactory.personalAccessToken();

    assertThat(first).isNotEqualTo(second);
    assertThat(TokenHash.hash(first)).isNotEqualTo(TokenHash.hash(second)).doesNotContain("rut-");
    assertThat(TokenHash.hash(first)).isNotEqualTo(first);
  }

  @Test
  @DisplayName("a personal access token is not shaped like a deploy token")
  void isNotADeployToken() {
    assertThat(TokenFactory.personalAccessToken()).doesNotStartWith("rdt-");
    assertThat(TokenFactory.deployToken()).doesNotStartWith("rut-");
  }
}
