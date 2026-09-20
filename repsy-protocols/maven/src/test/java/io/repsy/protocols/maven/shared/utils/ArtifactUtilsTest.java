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
package io.repsy.protocols.maven.shared.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

@DisplayName("ArtifactUtils.readModel")
class ArtifactUtilsTest {

  private static final String VALID_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
      </project>
      """;

  @Test
  @DisplayName("reads a well-formed POM")
  void readsAWellFormedPom() {
    final var model = ArtifactUtils.readModel(new ByteArrayResource(VALID_POM.getBytes(UTF_8)));

    assertThat(model).isNotNull();
    assertThat(model.getGroupId()).isEqualTo("com.example");
    assertThat(model.getArtifactId()).isEqualTo("lib");
    assertThat(model.getVersion()).isEqualTo("1.0");
  }

  @Test
  @DisplayName("answers a malformed POM with a fixed msgId that does not echo the file")
  void malformedPomYieldsFixedMessageId() {
    final var secret = "do-not-reflect-this-marker";
    final var malformed = "<project><modelVersion>4.0.0</modelVersion><!-- " + secret + " -->";

    assertThatThrownBy(
            () -> ArtifactUtils.readModel(new ByteArrayResource(malformed.getBytes(UTF_8))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile")
        .satisfies(e -> assertThat(e.toString()).doesNotContain(secret));
  }

  @Test
  @DisplayName("answers an unreadable POM with the same fixed msgId")
  void unreadablePomYieldsFixedMessageId() {
    final var unreadable =
        new InputStreamResource(
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("disk failure with /secret/path");
              }
            });

    assertThatThrownBy(() -> ArtifactUtils.readModel(unreadable))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");
  }
}
