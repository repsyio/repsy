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
package io.repsy.protocols.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RequestBodies")
class RequestBodiesTest {

  @Test
  @DisplayName("answers empty for a body with no byte in it")
  void emptyBody() throws IOException {
    assertThat(RequestBodies.nonEmpty(new ByteArrayInputStream(new byte[0]))).isEmpty();
  }

  @Test
  @DisplayName("answers the whole body, first byte included, when it has any")
  void nonEmptyBody() throws IOException {
    final var body = new byte[] {7, 8, 9};

    final var stream = RequestBodies.nonEmpty(new ByteArrayInputStream(body));

    assertThat(stream).isPresent();
    assertThat(stream.orElseThrow().readAllBytes()).containsExactly(body);
  }

  @Test
  @DisplayName("keeps a single zero byte, which is not an empty body")
  void zeroByte() throws IOException {
    final var stream = RequestBodies.nonEmpty(new ByteArrayInputStream(new byte[] {0}));

    assertThat(stream.orElseThrow().readAllBytes()).containsExactly(0);
  }

  @Test
  @DisplayName("propagates a failure to read the first byte")
  void failingBody() {
    final InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("boom");
          }
        };

    assertThatThrownBy(() -> RequestBodies.nonEmpty(failing))
        .isInstanceOf(IOException.class)
        .hasMessage("boom");
  }
}
