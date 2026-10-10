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
package io.repsy.os.shared.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the error codes in {@code ProtocolErrorCodes} and {@code ErrorConstants} (RPS-2017): the
 * main sources of the backend and of the protocol modules name a code through a constant, never as
 * a literal handed to an exception.
 */
@DisplayName("error code literals")
class ErrorCodeLiteralsTest {

  private static final Pattern INLINE_CODE =
      Pattern.compile(
          "new\\s+(?:BadRequest|ItemNotFound|ItemAlreadyExist|AccessNotAllowed|UnAuthorized"
              + "|SignatureNotVerified|Retryable)Exception\\(\\s*\"[A-Za-z0-9]+\"");

  private static final Pattern PRIVATE_CODE_CONSTANT =
      Pattern.compile("static\\s+final\\s+(?:@\\w+\\s+)?String\\s+ERR_\\w+\\s*=\\s*\"");

  private static List<Path> mainSources() throws IOException {
    final var roots = List.of(Path.of("src/main/java"), Path.of("../repsy-protocols"));
    return roots.stream()
        .flatMap(
            root -> {
              try (Stream<Path> walk = Files.walk(root)) {
                return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("src/main"))
                    .toList()
                    .stream();
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            })
        .toList();
  }

  @Test
  @DisplayName("no exception of the main sources carries an inline code literal")
  void noInlineCodeLiteral() throws IOException {
    final var sources = mainSources();
    final var offenders =
        sources.stream()
            .filter(
                p -> {
                  try {
                    return INLINE_CODE.matcher(Files.readString(p)).find();
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  }
                })
            .map(Path::toString)
            .toList();

    assertThat(sources).as("the main sources were found").hasSizeGreaterThan(500);
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("no class keeps a private ERR_ code constant of its own")
  void noPrivateCodeConstant() throws IOException {
    final var offenders =
        mainSources().stream()
            .filter(
                p -> {
                  try {
                    return PRIVATE_CODE_CONSTANT.matcher(Files.readString(p)).find();
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  }
                })
            .map(Path::toString)
            .toList();

    assertThat(offenders).isEmpty();
  }
}
