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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails when an integration test hashes a password per test instead of reusing a hash (RPS-1471,
 * RPS-1453).
 *
 * <p>A BCrypt hash at the production work factor costs about 100 ms of CPU. Before RPS-1453 every
 * integration test that made a user paid for one, about 60% of the CPU time of a full run. {@link
 * AbstractIT#VALID_PASSWORD_HASH} is the hash of the known test password, made once per JVM, and
 * {@code createUser} and the {@code *BearerToken()} helpers use it. Nothing stopped a new test from
 * calling {@code PasswordHasher.hash(...)} again, so this test scans the sources instead: it needs
 * no Docker and runs in {@code mvn test}.
 *
 * <p>Scope: every Java file under {@code src/test/java} that is not a unit test ({@code *Test}) and
 * not {@code AbstractIT}, which makes the shared hash. That is the integration tests and the
 * helpers they share. A unit test hashes once per class in a {@code static final} field and stays
 * out of scope.
 *
 * <p>Two rules, on {@code PasswordHasher.hash(} and {@code PasswordHasher::hash}:
 *
 * <ul>
 *   <li>Hashing {@code VALID_PASSWORD} (or its literal {@code "Password1!"}) is never allowed: use
 *       {@code VALID_PASSWORD_HASH}. This includes the allow-listed files.
 *   <li>Any other call is allowed only in the initializer of a {@code static final} field (made
 *       once per class), or in a file of {@link #ALLOWED} with the reason it needs a fresh hash per
 *       call. An entry that no longer calls {@code PasswordHasher.hash} fails too, so the list
 *       shrinks with the code.
 * </ul>
 *
 * <p>Comments are ignored. A static import of {@code hash} or a call through another name is not
 * seen; the calls above are how the tests spell it.
 */
@DisplayName("integration tests reuse the shared password hash")
class IntegrationTestHashingGuardTest {

  /** Surefire runs with the module directory ({@code repsy-backend}) as the working directory. */
  private static final Path TEST_SOURCES = Path.of("src", "test", "java");

  private static final String SHARED_HASH_OWNER = "AbstractIT.java";

  /**
   * Files that legitimately call {@code PasswordHasher.hash} outside a {@code static final} field,
   * with the reason. Add to it only when the test needs a hash of a password of its own, per case.
   */
  private static final Map<String, String> ALLOWED =
      Map.of(
          "AuthControllerIT.java",
          "hashes the password of each parameterized case of the legacy-password login test, and"
              + " every case has a different password the creation policy would reject");

  private static final Pattern HASH_CALL =
      Pattern.compile("\\bPasswordHasher\\s*(?:\\.\\s*hash\\s*\\(|::\\s*hash\\b)");

  private static final Pattern SHARED_PASSWORD_ARGUMENT =
      Pattern.compile("\\s*(?:VALID_PASSWORD|\"Password1!\")\\s*\\)");

  private static final Pattern STATIC_FINAL = Pattern.compile("\\bstatic\\s+final\\b");

  @Test
  @DisplayName("no integration test hashes a password per test where the shared hash serves")
  void integrationTestsDoNotHashPerTest() throws IOException {
    final var violations = new ArrayList<String>();
    final var callers = new ArrayList<String>();

    try (final Stream<Path> files = Files.walk(TEST_SOURCES)) {
      for (final var file : files.filter(IntegrationTestHashingGuardTest::inScope).toList()) {
        final var name = file.getFileName().toString();
        final var found = scan(name, read(file));

        violations.addAll(found.violations());

        if (found.callsHash()) {
          callers.add(name);
        }
      }
    }

    assertThat(violations).as(String.join("\n", violations)).isEmpty();
    final var stale =
        ALLOWED.keySet().stream().filter(name -> !callers.contains(name)).sorted().toList();

    assertThat(stale)
        .as(
            "IntegrationTestHashingGuardTest.ALLOWED lists files that do not call"
                + " PasswordHasher.hash any more: remove the entries")
        .isEmpty();
  }

  @Test
  @DisplayName("a hash of the shared test password is reported with the fix")
  void reportsTheSharedPassword() {
    final var source =
        """
        class SomeIT extends AbstractIT {
          void t() {
            final var hash = PasswordHasher.hash(VALID_PASSWORD);
          }
        }
        """;

    final var found = scan("SomeIT.java", source);

    assertThat(found.violations())
        .singleElement()
        .asString()
        .startsWith("SomeIT.java:3")
        .contains("VALID_PASSWORD_HASH");
  }

  @Test
  @DisplayName("a hash of the literal shared password and a method reference are reported")
  void reportsTheLiteralAndTheMethodReference() {
    final var source =
        """
        class SomeIT {
          void t() {
            PasswordHasher.hash( "Password1!" );
            java.util.function.Function<String, String> f = PasswordHasher::hash;
          }
        }
        """;

    assertThat(scan("SomeIT.java", source).violations())
        .hasSize(2)
        .allSatisfy(message -> assertThat(message).startsWith("SomeIT.java:"));
  }

  @Test
  @DisplayName("a hash made per test of another password is reported and names both fixes")
  void reportsAPerTestHash() {
    final var source =
        """
        class SomeIT {
          void t() {
            final var hash = PasswordHasher
                .hash("Another1!");
          }
        }
        """;

    assertThat(scan("SomeIT.java", source).violations())
        .singleElement()
        .asString()
        .startsWith("SomeIT.java:3")
        .contains("static final")
        .contains("ALLOWED");
  }

  @Test
  @DisplayName("a hash in a static final field, made once per class, is accepted")
  void acceptsAStaticFinalField() {
    final var source =
        """
        class SomeIT {
          private static final String OTHER_HASH =
              PasswordHasher.hash("Another1!");
        }
        """;

    final var found = scan("SomeIT.java", source);

    assertThat(found.violations()).isEmpty();
    assertThat(found.callsHash()).isTrue();
  }

  @Test
  @DisplayName("a hash in a comment or a javadoc link is not a call")
  void ignoresComments() {
    final var source =
        """
        class SomeIT {
          // PasswordHasher.hash(VALID_PASSWORD) is what not to write
          /** {@link PasswordHasher#hash} and PasswordHasher.hash("x") in a comment. */
          void t() {}
        }
        """;

    final var found = scan("SomeIT.java", source);

    assertThat(found.violations()).isEmpty();
    assertThat(found.callsHash()).isFalse();
  }

  @Test
  @DisplayName("an allow-listed file may hash its own passwords but not the shared one")
  void allowListDoesNotExcuseTheSharedPassword() {
    final var own =
        """
        class AuthControllerIT {
          void t(String p) {
            PasswordHasher.hash(p);
          }
        }
        """;
    final var shared =
        """
        class AuthControllerIT {
          void t() {
            PasswordHasher.hash(VALID_PASSWORD);
          }
        }
        """;

    assertThat(scan("AuthControllerIT.java", own).violations()).isEmpty();
    assertThat(scan("AuthControllerIT.java", shared).violations()).hasSize(1);
  }

  private static boolean inScope(final Path file) {
    final var name = file.getFileName().toString();

    return name.endsWith(".java") && !name.endsWith("Test.java") && !name.equals(SHARED_HASH_OWNER);
  }

  private static String read(final Path file) {
    try {
      return Files.readString(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** What one file does with {@code PasswordHasher.hash}. */
  private record Scan(List<String> violations, boolean callsHash) {}

  private static Scan scan(final String fileName, final String source) {
    final var code = withoutComments(source);
    final var violations = new ArrayList<String>();
    final var matcher = HASH_CALL.matcher(code);
    var callsHash = false;

    while (matcher.find()) {
      callsHash = true;
      final var line =
          1 + (int) code.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
      final var where = "%s:%d".formatted(fileName, line);

      if (SHARED_PASSWORD_ARGUMENT.matcher(code).region(matcher.end(), code.length()).lookingAt()) {
        violations.add(
            where
                + " hashes the shared test password again, which costs about 100 ms of CPU per"
                + " call (RPS-1453). Use AbstractIT.VALID_PASSWORD_HASH.");
      } else if (!inStaticFinalField(code, matcher.start()) && !ALLOWED.containsKey(fileName)) {
        violations.add(
            where
                + " calls PasswordHasher.hash per test, and a BCrypt hash costs about 100 ms of CPU"
                + " (RPS-1453). Use AbstractIT.VALID_PASSWORD_HASH for the shared"
                + " test password; for a password of its own, hash it once in a private static"
                + " final field. Only if the test needs a fresh hash per call, add"
                + " \"%s\" to IntegrationTestHashingGuardTest.ALLOWED with the reason."
                    .formatted(fileName));
      }
    }

    return new Scan(violations, callsHash);
  }

  /**
   * Whether the statement that holds the call is the initializer of a {@code static final} field.
   */
  private static boolean inStaticFinalField(final String code, final int callStart) {
    final var statementStart =
        Math.max(
                Math.max(code.lastIndexOf(';', callStart), code.lastIndexOf('{', callStart)),
                code.lastIndexOf('}', callStart))
            + 1;

    return STATIC_FINAL.matcher(code.substring(statementStart, callStart)).find();
  }

  /** Blanks out comments, keeping every newline so that line numbers stay right. */
  private static String withoutComments(final String source) {
    final var out = new StringBuilder(source.length());
    var i = 0;

    while (i < source.length()) {
      final var c = source.charAt(i);

      if (source.startsWith("\"\"\"", i)) {
        final var close = source.indexOf("\"\"\"", i + 3);
        final var end = close < 0 ? source.length() : close + 3;
        out.append(source, i, end);
        i = end;
      } else if (c == '"') {
        final var end = endOfString(source, i);
        out.append(source, i, end);
        i = end;
      } else if (source.startsWith("//", i)) {
        while (i < source.length() && source.charAt(i) != '\n') {
          i++;
        }
      } else if (source.startsWith("/*", i)) {
        final var close = source.indexOf("*/", i + 2);
        final var end = close < 0 ? source.length() : close + 2;

        source.substring(i, end).chars().filter(ch -> ch == '\n').forEach(_ -> out.append('\n'));
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }

    return out.toString();
  }

  private static int endOfString(final String source, final int start) {
    var i = start + 1;

    while (i < source.length() && source.charAt(i) != '"') {
      i += source.charAt(i) == '\\' ? 2 : 1;
    }

    return Math.min(i + 1, source.length());
  }
}
