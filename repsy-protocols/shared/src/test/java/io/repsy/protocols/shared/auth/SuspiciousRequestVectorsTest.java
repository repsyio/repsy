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
package io.repsy.protocols.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the shared test vectors of the probe ban (RPS-2633) against the classifier. The file is the
 * contract that repsy-cli's port of the classifier is tested against, so it must describe exactly
 * what the server does: every case lists the whole set of families on each port, every family has a
 * case, and every needle of the classifier is hit by a case.
 */
@DisplayName("SuspiciousRequestClassifier shared test vectors (RPS-2633)")
class SuspiciousRequestVectorsTest {

  static final String VECTORS = "suspicious-request-vectors.json";

  private final SuspiciousRequestClassifier classifier =
      new SuspiciousRequestClassifier(
          SuspiciousRequestProperties.of(ThrottleMode.ENFORCE, 10, 3600));

  /** One case of the file, with its URI already expanded. */
  record Vector(
      String name,
      String method,
      String uri,
      String userAgent,
      boolean browserNavigation,
      Set<SignalFamily> api,
      Set<SignalFamily> protocol) {

    @Override
    public String toString() {

      return this.name;
    }
  }

  static String json() {

    try (InputStream in = SuspiciousRequestVectorsTest.class.getResourceAsStream(VECTORS)) {
      return new String(Objects.requireNonNull(in, VECTORS).readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException("reading " + VECTORS, e);
    }
  }

  static List<Vector> vectors() {

    final List<Map<String, Object>> cases = JsonPath.read(json(), "$.cases");
    final var out = new ArrayList<Vector>();

    for (final var c : cases) {
      out.add(
          new Vector(
              (String) c.get("name"),
              (String) c.getOrDefault("method", "GET"),
              uriOf(c),
              (String) c.getOrDefault("userAgent", ""),
              (Boolean) c.getOrDefault("browserNavigation", false),
              families(c.get("api")),
              families(c.get("protocol"))));
    }

    return out;
  }

  @SuppressWarnings("unchecked")
  private static String uriOf(final Map<String, Object> c) {

    final var uri = new StringBuilder((String) c.get("uri"));
    final var repeat = (Map<String, Object>) c.get("repeat");

    if (repeat != null) {
      uri.append(((String) repeat.get("text")).repeat((Integer) repeat.get("times")));
    }
    if (c.get("suffix") != null) {
      uri.append((String) c.get("suffix"));
    }

    return uri.toString();
  }

  @SuppressWarnings("unchecked")
  private static Set<SignalFamily> families(final Object names) {

    final var set = EnumSet.noneOf(SignalFamily.class);

    for (final var name : Objects.requireNonNull((List<String>) names, "api and protocol")) {
      set.add(SignalFamily.valueOf(name));
    }

    return set;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  @DisplayName("each vector yields exactly its families on each port")
  void vectorMatchesTheClassifier(final Vector v) {

    assertThat(
            this.classifier.classify(
                v.method(), v.uri(), v.userAgent(), v.browserNavigation(), true))
        .as("API port")
        .isEqualTo(v.api());
    assertThat(
            this.classifier.classify(
                v.method(), v.uri(), v.userAgent(), v.browserNavigation(), false))
        .as("protocol port")
        .isEqualTo(v.protocol());
  }

  @Test
  @DisplayName("the names of the vectors are unique")
  void namesAreUnique() {

    final var names = vectors().stream().map(Vector::name).toList();

    assertThat(names).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("every family has a vector that reports it, and some vectors report none")
  void everyFamilyIsCovered() {

    final var seen = EnumSet.noneOf(SignalFamily.class);
    var clean = 0;

    for (final var v : vectors()) {
      seen.addAll(v.api());
      seen.addAll(v.protocol());
      if (v.api().isEmpty() && v.protocol().isEmpty()) {
        clean++;
      }
    }

    assertThat(seen).isEqualTo(EnumSet.allOf(SignalFamily.class));
    assertThat(clean).as("vectors of legitimate traffic").isGreaterThan(20);
  }

  @Test
  @DisplayName("every needle of the classifier is hit by a vector of its family")
  void everyNeedleHasAVector() {

    final var vectors = vectors();
    final var missing = new ArrayList<String>();

    for (final var entry : SuspiciousRequestClassifier.needles().entrySet()) {
      final var family = entry.getKey();

      for (final var needle : entry.getValue()) {
        final var hit =
            vectors.stream()
                .filter(v -> v.api().contains(family) || v.protocol().contains(family))
                .anyMatch(v -> haystack(family, v).contains(needle.toLowerCase(Locale.ROOT)));
        if (!hit) {
          missing.add(family + " " + needle);
        }
      }
    }

    assertThat(missing).as("needles without a vector in " + VECTORS).isEmpty();
  }

  /** Where the needle of a family is looked for, as the classifier does, in lower case. */
  private static String haystack(final SignalFamily family, final Vector v) {

    return switch (family) {
      case METHOD_ABUSE -> v.method().toLowerCase(Locale.ROOT);
      case SCANNER_UA -> v.userAgent().toLowerCase(Locale.ROOT);
      default -> {
        final var lower = v.uri().toLowerCase(Locale.ROOT);
        final var twice =
            SuspiciousRequestClassifier.decode(SuspiciousRequestClassifier.decode(lower));
        yield lower + " " + twice + " " + twice.replace('+', ' ');
      }
    };
  }

  @Test
  @DisplayName("the family table of the file matches SignalFamily")
  void familyTableMatchesTheEnum() {

    final List<Map<String, Object>> table = JsonPath.read(json(), "$.families");

    assertThat(table.stream().map(row -> (String) row.get("name")).toList())
        .isEqualTo(Arrays.stream(SignalFamily.values()).map(Enum::name).toList());
    for (final var row : table) {
      final var family = SignalFamily.valueOf((String) row.get("name"));

      assertThat(row)
          .as(family.name())
          .containsEntry("key", family.key())
          .containsEntry("weight", family.defaultWeight())
          .containsEntry("apiPortOnly", family.apiPortOnly())
          .containsEntry("bansAlone", family.bansAlone());
    }
  }

  @Test
  @DisplayName("the limits of the file match the classifier")
  void limitsMatchTheClassifier() {

    final Integer overlong = JsonPath.read(json(), "$.overlongUriLength");
    final Integer inspected = JsonPath.read(json(), "$.maxInspectedLength");

    assertThat(overlong).isEqualTo(SuspiciousRequestClassifier.OVERLONG_URI_LENGTH);
    assertThat(inspected).isEqualTo(SuspiciousRequestClassifier.MAX_INSPECTED_LENGTH);
  }

  @Test
  @DisplayName("a case only uses the keys the file documents")
  void casesUseOnlyDocumentedKeys() {

    final Map<String, Object> format = JsonPath.read(json(), "$.format");
    final List<Map<String, Object>> cases = JsonPath.read(json(), "$.cases");
    final var allowed = Stream.concat(Stream.of("name"), format.keySet().stream()).toList();
    final var unknown =
        cases.stream()
            .flatMap(c -> c.keySet().stream())
            .filter(key -> !allowed.contains(key))
            .collect(Collectors.toSet());

    assertThat(unknown).isEmpty();
  }
}
