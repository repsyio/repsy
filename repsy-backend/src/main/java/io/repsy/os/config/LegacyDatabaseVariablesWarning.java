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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Warns at startup when {@code DB_HOST}, {@code DB_PORT} or {@code DB_DATABASE} is set (RPS-1423).
 *
 * <p>Releases up to v26.08.4 built the PostgreSQL url from those variables. Only {@code DB_URL} is
 * read now (RPS-1173), and the Docker image defaults it to an embedded H2 file, so a container that
 * is upgraded with its old variables would otherwise start on an empty H2 database without a word.
 * The listener only warns: refusing to start would break setups that still export the variables for
 * other tools.
 *
 * <p>It runs on {@link ApplicationEnvironmentPreparedEvent}, when application.yml is loaded and the
 * effective {@code spring.datasource.url} is known, and after the logging system is initialised
 * (ordered after {@code LoggingApplicationListener}). It is registered in {@code
 * META-INF/spring.factories} so that a {@code @SpringBootTest} runs it too.
 */
public class LegacyDatabaseVariablesWarning
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger(LegacyDatabaseVariablesWarning.class);

  /** Just after {@code LoggingApplicationListener} ({@code HIGHEST_PRECEDENCE + 20}). */
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

  private static final String HOST = "DB_HOST";
  private static final String PORT = "DB_PORT";
  private static final String DATABASE = "DB_DATABASE";

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void onApplicationEvent(final ApplicationEnvironmentPreparedEvent event) {
    warningFor(event.getEnvironment()).ifPresent(LOG::warn);
  }

  /**
   * The warning to log for this environment, or empty when none of the removed variables is set.
   * Never contains {@code DB_URL} or the password: it may carry credentials, so only the host, port
   * and database that the operator set are repeated.
   */
  static Optional<String> warningFor(final Environment env) {
    final String host = env.getProperty(HOST);
    final String port = env.getProperty(PORT);
    final String database = env.getProperty(DATABASE);
    if (host == null && port == null && database == null) {
      return Optional.empty();
    }

    final var message = new StringBuilder("The environment variables ");
    message.append(String.join(", ", setNames(host, port, database)));
    message.append(" are no longer read: only DB_URL selects the database. ");
    message
        .append("To keep using PostgreSQL set DB_URL=jdbc:postgresql://")
        .append(orDefault(host, "localhost"))
        .append(':')
        .append(orDefault(port, "5432"))
        .append('/')
        .append(orDefault(database, "repsy"))
        .append(" (with DB_USERNAME and DB_PASSWORD).");

    final String url = env.getProperty("spring.datasource.url", "");
    if (url.startsWith("jdbc:h2:")) {
      message.append(
          " Repsy is starting on the embedded H2 database, not PostgreSQL: data stored in an"
              + " existing PostgreSQL database is not visible until DB_URL points to it.");
    }
    return Optional.of(message.toString());
  }

  private static List<String> setNames(
      final String host, final String port, final String database) {
    final var names = new ArrayList<String>(3);
    if (host != null) {
      names.add(HOST);
    }
    if (port != null) {
      names.add(PORT);
    }
    if (database != null) {
      names.add(DATABASE);
    }
    return names;
  }

  private static String orDefault(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
