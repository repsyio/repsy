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
package io.repsy.protocols.shared.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolErrorCodes")
class ProtocolErrorCodesTest {

  private static List<String> values() throws IllegalAccessException {
    final var values = new ArrayList<String>();
    for (final Field field : ProtocolErrorCodes.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        values.add((String) field.get(null));
      }
    }
    return values;
  }

  @Test
  @DisplayName("holds exactly the codes pinned in protocol-error-codes.txt (the wire values)")
  void holdsThePinnedCodes() throws IOException, IllegalAccessException {
    final var pinned =
        Files.readAllLines(
            Path.of("src/test/resources/protocol-error-codes.txt"), StandardCharsets.UTF_8);

    assertThat(values()).containsExactlyInAnyOrderElementsOf(pinned);
  }

  @Test
  @DisplayName("every constant is the UPPER_SNAKE_CASE of its camelCase value")
  void namesFollowValues() throws IllegalAccessException {
    for (final Field field : ProtocolErrorCodes.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      final var value = (String) field.get(null);
      assertThat(value).matches("[a-z][A-Za-z0-9]*");
      assertThat(field.getName())
          .isEqualTo(value.replaceAll("(?<=[a-z0-9])([A-Z])", "_$1").toUpperCase());
    }
  }
}
