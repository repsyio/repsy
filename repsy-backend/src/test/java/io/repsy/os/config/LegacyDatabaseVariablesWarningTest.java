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
package io.repsy.os.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LegacyDatabaseVariablesWarning: RPS-1423, the removed DB_HOST/DB_PORT/DB_DATABASE")
@ExtendWith(OutputCaptureExtension.class)
class LegacyDatabaseVariablesWarningTest {

  private static final String H2_URL = "jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL";
  private static final String PG_URL = "jdbc:postgresql://db.example:5432/repsy";

  @Test
  @DisplayName("nothing is warned when none of the removed variables is set")
  void noVariableNoWarning() {
    final var env = new MockEnvironment().withProperty("spring.datasource.url", H2_URL);

    assertThat(LegacyDatabaseVariablesWarning.warningFor(env)).isEmpty();
  }

  @Test
  @DisplayName("a lone DB_HOST warns, names only that variable and suggests a DB_URL with defaults")
  void loneHostWarns() {
    final var env =
        new MockEnvironment()
            .withProperty("DB_HOST", "pg.internal")
            .withProperty("spring.datasource.url", PG_URL);

    final var message = LegacyDatabaseVariablesWarning.warningFor(env).orElseThrow();

    assertThat(message)
        .contains("DB_HOST are no longer read")
        .doesNotContain("DB_PORT")
        .doesNotContain("DB_DATABASE")
        .contains("DB_URL=jdbc:postgresql://pg.internal:5432/repsy")
        .doesNotContain("H2");
  }

  @Test
  @DisplayName("all three variables on an H2 url say plainly that Repsy started on H2")
  void allThreeOnH2() {
    final var env =
        new MockEnvironment()
            .withProperty("DB_HOST", "pg.internal")
            .withProperty("DB_PORT", "6543")
            .withProperty("DB_DATABASE", "packages")
            .withProperty("spring.datasource.url", H2_URL);

    final var message = LegacyDatabaseVariablesWarning.warningFor(env).orElseThrow();

    assertThat(message)
        .contains("DB_HOST, DB_PORT, DB_DATABASE are no longer read")
        .contains("DB_URL=jdbc:postgresql://pg.internal:6543/packages")
        .contains("starting on the embedded H2 database, not PostgreSQL")
        .contains("not visible");
  }

  @Test
  @DisplayName("all three variables on a PostgreSQL url still warn, without the H2 sentence")
  void allThreeOnPostgres() {
    final var env =
        new MockEnvironment()
            .withProperty("DB_HOST", "pg.internal")
            .withProperty("DB_PORT", "5432")
            .withProperty("DB_DATABASE", "repsy")
            .withProperty("spring.datasource.url", PG_URL);

    final var message = LegacyDatabaseVariablesWarning.warningFor(env).orElseThrow();

    assertThat(message).contains("no longer read").doesNotContain("H2");
  }

  @Test
  @DisplayName("a blank value falls back to the default in the suggested DB_URL")
  void blankValueFallsBack() {
    final var env = new MockEnvironment().withProperty("DB_PORT", " ");

    assertThat(LegacyDatabaseVariablesWarning.warningFor(env).orElseThrow())
        .contains("DB_URL=jdbc:postgresql://localhost:5432/repsy");
  }

  @Test
  @DisplayName("the message never repeats DB_URL, DB_USERNAME's value or the password")
  void neverLeaksCredentials() {
    final var env =
        new MockEnvironment()
            .withProperty("DB_HOST", "pg.internal")
            .withProperty("DB_PASSWORD", "s3cr3t-pass")
            .withProperty("DB_URL", "jdbc:postgresql://u:s3cr3t-url@x/y")
            .withProperty("spring.datasource.password", "s3cr3t-pass")
            .withProperty("spring.datasource.url", "jdbc:h2:file:/x;PASSWORD=s3cr3t-url");

    final var message = LegacyDatabaseVariablesWarning.warningFor(env).orElseThrow();

    assertThat(message).doesNotContain("s3cr3t").doesNotContain("DB_PASSWORD=");
    assertThat(message).doesNotContain("jdbc:h2");
  }

  @Test
  @DisplayName("the listener logs the warning at WARN and never throws")
  void listenerLogsTheWarning(final CapturedOutput output) {
    final var env = new MockEnvironment().withProperty("DB_HOST", "pg.internal");

    new LegacyDatabaseVariablesWarning().onApplicationEvent(event(env));

    assertThat(output.getAll()).contains("WARN").contains("DB_HOST are no longer read");
  }

  @Test
  @DisplayName("the listener is silent when the removed variables are not set")
  void listenerIsSilentWithoutVariables(final CapturedOutput output) {
    new LegacyDatabaseVariablesWarning().onApplicationEvent(event(new MockEnvironment()));

    assertThat(output.getAll()).doesNotContain("no longer read");
  }

  @Test
  @DisplayName("it runs after LoggingApplicationListener (HIGHEST_PRECEDENCE + 20)")
  void runsAfterLoggingIsInitialised() {
    assertThat(new LegacyDatabaseVariablesWarning().getOrder())
        .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 20);
  }

  private static ApplicationEnvironmentPreparedEvent event(final MockEnvironment env) {
    return new ApplicationEnvironmentPreparedEvent(
        new DefaultBootstrapContext(), new SpringApplication(Object.class), new String[0], env);
  }
}
