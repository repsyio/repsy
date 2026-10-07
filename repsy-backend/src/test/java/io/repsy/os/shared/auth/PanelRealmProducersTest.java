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
package io.repsy.os.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1903: no personal access token is ever turned into a panel token. A personal access token is
 * exchanged for a protocol JWT (npm, Cargo, Docker) and nothing else; the {@code aud=panel} realm
 * belongs to a login. Every panel token is made by {@code JwtUtils#createSessionAccessToken}, and
 * outside {@code JwtUtils} only {@code LoginInfoFactory} (the login and the refresh) calls it. A
 * new caller, or a new place that names the panel audience, fails here and has to be looked at: if
 * it can be reached with a personal access token, it breaks the rule.
 *
 * <p>The behaviour is checked against a real exchange in {@code PatDerivedTokensArePanelProofIT}.
 * This test scans the main sources, so it needs no Docker and runs in {@code mvn test}.
 */
@DisplayName("only a login makes a panel token")
class PanelRealmProducersTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  /** The files that may make a panel token, with the reason. */
  private static final String JWT_UTILS = "JwtUtils.java";

  private static final String LOGIN_FACTORY = "LoginInfoFactory.java";

  private static final Pattern PANEL_TOKEN_CALL =
      Pattern.compile("\\b(?:createSessionAccessToken|createPanelAccessToken)\\s*\\(");

  private static final Pattern PANEL_AUDIENCE = Pattern.compile("TokenRealm\\s*\\.\\s*PANEL\\b");

  private static String read(final Path file) {
    try {
      return Files.readString(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("createSessionAccessToken and createPanelAccessToken have no caller but the login")
  void panelTokensAreMadeByTheLoginOnly() throws IOException {
    final var callers = new ArrayList<String>();

    try (final Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      for (final var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        final var name = file.getFileName().toString();

        if (!JWT_UTILS.equals(name) && PANEL_TOKEN_CALL.matcher(read(file)).find()) {
          callers.add(name);
        }
      }
    }

    assertThat(callers)
        .as("who makes a panel token, besides JwtUtils itself")
        .containsExactly(LOGIN_FACTORY);
  }

  @Test
  @DisplayName("the panel audience is named in JwtUtils only")
  void thePanelAudienceIsNamedInJwtUtilsOnly() throws IOException {
    final var naming = new ArrayList<String>();

    try (final Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      for (final var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        final var name = file.getFileName().toString();

        if (!JWT_UTILS.equals(name) && PANEL_AUDIENCE.matcher(read(file)).find()) {
          naming.add(name);
        }
      }
    }

    assertThat(naming).as("files that name TokenRealm.PANEL besides JwtUtils").isEmpty();
  }

  @Test
  @DisplayName("JwtUtils puts the panel audience on one token only, the session access token")
  void oneProducerInJwtUtils() {
    final var source =
        read(
            MAIN_SOURCES.resolve(
                Path.of("io", "repsy", "os", "shared", "auth", "utils", JWT_UTILS)));
    final var audiences =
        Pattern.compile("withAudience\\(\\s*TokenRealm\\.PANEL").matcher(source).results().count();

    assertThat(audiences).as("withAudience(TokenRealm.PANEL...) calls in JwtUtils").isEqualTo(1);
  }
}
