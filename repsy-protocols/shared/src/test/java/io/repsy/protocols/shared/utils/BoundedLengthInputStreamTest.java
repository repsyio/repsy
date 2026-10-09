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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BoundedLengthInputStream")
class BoundedLengthInputStreamTest {

  @Test
  @DisplayName("reports end-of-stream after the bound although the delegate has more")
  void stopsAtTheBound() throws IOException {
    final var in = new BoundedLengthInputStream(new ByteArrayInputStream(new byte[10]), 4);

    assertThat(in.readAllBytes()).hasSize(4);
    assertThat(in.read()).isEqualTo(-1);
  }

  @Test
  @DisplayName("reads fewer bytes when the delegate ends before the bound")
  void delegateEndsFirst() throws IOException {
    final var in = new BoundedLengthInputStream(new ByteArrayInputStream(new byte[3]), 8);

    assertThat(in.readAllBytes()).hasSize(3);
  }

  @Test
  @DisplayName("bounds the single byte reads too")
  void boundsSingleByteReads() throws IOException {
    final var in = new BoundedLengthInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2);

    assertThat(in.read()).isEqualTo(1);
    assertThat(in.read()).isEqualTo(2);
    assertThat(in.read()).isEqualTo(-1);
  }
}
