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
package io.repsy.protocols.npm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("NpmPayloadUtils shape checks (RPS-2210)")
@SuppressWarnings("unchecked")
class NpmPayloadShapeTest {

  private static Map<String, Object> payload() {
    final var version = new HashMap<String, Object>();
    version.put("name", "demo");
    version.put("version", "1.0.0");
    version.put("dist", new HashMap<>(Map.of("tarball", "http://h/demo/-/demo-1.0.0.tgz")));

    final var payload = new LinkedHashMap<String, Object>();
    payload.put("name", "demo");
    payload.put("versions", new LinkedHashMap<>(Map.of("1.0.0", version)));
    payload.put("dist-tags", new LinkedHashMap<>(Map.of("latest", "1.0.0")));
    payload.put("_attachments", new HashMap<>(Map.of("demo-1.0.0.tgz", Map.of("data", "dGFy"))));

    return payload;
  }

  private static Arguments edit(final String label, final Consumer<Map<String, Object>> edit) {
    return Arguments.of(label, edit);
  }

  private static Map<String, Object> version(final Map<String, Object> payload) {
    return (Map<String, Object>) ((Map<String, Object>) payload.get("versions")).get("1.0.0");
  }

  @Test
  @DisplayName("accepts what npm publish sends")
  void acceptsValidPayload() {
    final var payload = payload();

    assertThatCode(
            () -> {
              NpmPayloadUtils.checkPayloadShape(payload);
              NpmPayloadUtils.checkPublishPayload(payload);
            })
        .doesNotThrowAnyException();
  }

  static Stream<Arguments> badShapes() {
    return Stream.of(
        edit("versions list", p -> p.put("versions", List.of())),
        edit("versions absent", p -> p.remove("versions")),
        edit("version entry number", p -> p.put("versions", Map.of("1.0.0", 5))),
        edit("dist-tags string", p -> p.put("dist-tags", "latest")),
        edit("dist-tag value number", p -> p.put("dist-tags", Map.of("latest", 5))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badShapes")
  @DisplayName("checkPayloadShape refuses a versions or dist-tags of the wrong type")
  void refusesBadShape(final String label, final Consumer<Map<String, Object>> edit) {
    final var payload = payload();
    edit.accept(payload);

    assertThatThrownBy(() -> NpmPayloadUtils.checkPayloadShape(payload))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("badRequest");
  }

  static Stream<Arguments> badPublishes() {
    return Stream.of(
        edit("versions empty", p -> p.put("versions", Map.of())),
        edit("dist-tags empty", p -> p.put("dist-tags", Map.of())),
        edit("dist-tags absent", p -> p.remove("dist-tags")),
        edit("version name absent", p -> version(p).remove("name")),
        edit("dist absent", p -> version(p).remove("dist")),
        edit("dist string", p -> version(p).put("dist", "x")),
        edit("tarball absent", p -> version(p).put("dist", Map.of())),
        edit("tarball number", p -> version(p).put("dist", Map.of("tarball", 5))),
        edit("attachments absent", p -> p.remove("_attachments")),
        edit("attachments empty", p -> p.put("_attachments", Map.of())),
        edit("attachment number", p -> p.put("_attachments", Map.of("f", 5))),
        edit("data number", p -> p.put("_attachments", Map.of("f", Map.of("data", 5)))),
        edit("data absent", p -> p.put("_attachments", Map.of("f", Map.of()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badPublishes")
  @DisplayName("checkPublishPayload refuses a publish missing what it cannot store without")
  void refusesBadPublish(final String label, final Consumer<Map<String, Object>> edit) {
    final var payload = payload();
    edit.accept(payload);

    assertThatThrownBy(() -> NpmPayloadUtils.checkPublishPayload(payload))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("badRequest");
  }

  @Test
  @DisplayName("a non-string version field is an invalid package version")
  void refusesNonStringVersionField() {
    final var payload = payload();
    version(payload).put("version", 1);

    assertThatThrownBy(() -> NpmPayloadUtils.checkPublishPayload(payload))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalidPackageVersion");
  }

  @Test
  @DisplayName("a declared name that is not a string is a package name mismatch")
  void refusesNonStringDeclaredName() {
    final var payload = payload();
    payload.put("_id", Map.of("a", 1));

    assertThatThrownBy(() -> NpmPayloadUtils.checkPackageNameMatchesUrl(payload, null, "demo"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("packageNameMismatch");
  }

  @Test
  @DisplayName("a non-integer or non-numeric attachment length is measured or ignored")
  void tarballLengthTakesAnyNumber() {
    final var payload = payload();
    final var attachment =
        (Map<String, Object>)
            ((Map<String, Object>) payload.get("_attachments")).get("demo-1.0.0.tgz");
    final var mutable = new HashMap<>(attachment);
    mutable.put("length", 5_000_000_000L);
    payload.put("_attachments", Map.of("demo-1.0.0.tgz", mutable));

    assertThat(NpmPayloadUtils.getTarballLength(payload)).isEqualTo(5_000_000_000L);

    mutable.put("length", "x");

    assertThat(NpmPayloadUtils.getTarballLength(payload)).isEqualTo(3L);
  }

  @Test
  @DisplayName("a dist-tag that names a version of the body needs no stored versions (RPS-2219)")
  void tagOfBodyVersionIsKept() {
    final var payload = payload();

    assertThatCode(
            () ->
                NpmPayloadUtils.checkDistTagTargets(
                    payload,
                    () -> {
                      throw new AssertionError("stored versions must not be read");
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a dist-tag that names a stored version is kept, an unknown one is a 400 (RPS-2219)")
  void tagOfUnknownVersionIsRefused() {
    final var payload = payload();
    ((Map<String, Object>) payload.get("dist-tags")).put("beta", "0.9.0");

    assertThatCode(() -> NpmPayloadUtils.checkDistTagTargets(payload, () -> List.of("0.9.0")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> NpmPayloadUtils.checkDistTagTargets(payload, () -> List.of("0.8.0")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> NpmPayloadUtils.checkDistTagTargets(payload, List::of))
        .isInstanceOf(BadRequestException.class);
  }

  private static Map<String, Object> versionsOf(final Object deprecated, final boolean present) {
    final var version = new HashMap<String, Object>();
    version.put("name", "demo");

    if (present) {
      version.put("deprecated", deprecated);
    }

    return new HashMap<>(Map.of("versions", new HashMap<>(Map.of("1.0.0", version))));
  }

  @Test
  @DisplayName("findDeprecatedVersions reads a string, skips null or absent (RPS-2219)")
  void findDeprecatedVersionsStrings() {
    final var old = versionsOf(null, false);

    assertThat(NpmPayloadUtils.findDeprecatedVersions(old, versionsOf("use 2.x", true)))
        .extracting(pair -> pair.getSecond())
        .containsExactly("use 2.x");
    assertThat(NpmPayloadUtils.findDeprecatedVersions(old, versionsOf(null, true))).isEmpty();
    assertThat(NpmPayloadUtils.findDeprecatedVersions(old, versionsOf(null, false))).isEmpty();
  }

  @ParameterizedTest(name = "deprecated {0} is a 400, not a ClassCastException")
  @MethodSource("nonStringDeprecated")
  @DisplayName("findDeprecatedVersions refuses a deprecated that is not a string (RPS-2219)")
  void findDeprecatedVersionsRefusesNonString(final String label, final Object value) {
    final var stored = versionsOf("old message", true);

    assertThatThrownBy(
            () -> NpmPayloadUtils.findDeprecatedVersions(stored, versionsOf(value, true)))
        .as(label)
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                NpmPayloadUtils.findDeprecatedVersions(
                    versionsOf(null, false), versionsOf(value, true)))
        .as(label)
        .isInstanceOf(BadRequestException.class);
  }

  static Stream<Arguments> nonStringDeprecated() {
    return Stream.of(
        Arguments.of("number", 5),
        Arguments.of("boolean", true),
        Arguments.of("object", Map.of("a", 1)),
        Arguments.of("list", List.of("x")));
  }
}
