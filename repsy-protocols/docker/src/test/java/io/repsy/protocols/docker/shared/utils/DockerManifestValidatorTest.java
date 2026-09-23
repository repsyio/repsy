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
package io.repsy.protocols.docker.shared.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DockerManifestValidator")
class DockerManifestValidatorTest {

  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String DOCKER_MANIFEST =
      "application/vnd.docker.distribution.manifest.v2+json";
  private static final String DOCKER_MANIFEST_SCHEMA1 =
      "application/vnd.docker.distribution.manifest.v1+json";
  private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
  private static final String DOCKER_LIST =
      "application/vnd.docker.distribution.manifest.list.v2+json";

  private static final String CONFIG = "{\"mediaType\":\"cfg\",\"digest\":\"sha256:c\",\"size\":2}";
  private static final String LAYER =
      "{\"mediaType\":\"layer\",\"digest\":\"sha256:l\",\"size\":3}";
  private static final String PLATFORM = "{\"architecture\":\"amd64\",\"os\":\"linux\"}";
  private static final String ENTRY =
      "{\"digest\":\"sha256:m\",\"size\":9,\"platform\":" + PLATFORM + "}";

  private static Stream<String> imageManifestTypes() {
    return Stream.of(OCI_MANIFEST, DOCKER_MANIFEST);
  }

  private static Stream<String> indexTypes() {
    return Stream.of(OCI_INDEX, DOCKER_LIST);
  }

  private static String image(final String config, final String layers) {
    return "{\"schemaVersion\":2,\"config\":" + config + ",\"layers\":" + layers + "}";
  }

  private static String index(final String manifests) {
    return "{\"schemaVersion\":2,\"manifests\":" + manifests + "}";
  }

  private static Stream<Arguments> malformedBodies() {
    return Stream.of(
        Arguments.of("not json", "manifestInvalidJson"),
        Arguments.of("", "manifestInvalidJson"),
        Arguments.of("{\"schemaVersion\":2,", "manifestInvalidJson"),
        Arguments.of("[]", "manifestInvalidJson"),
        Arguments.of("\"text\"", "manifestInvalidJson"),
        Arguments.of("null", "manifestInvalidJson"),
        Arguments.of("42", "manifestInvalidJson"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedBodies")
  @DisplayName("refuses a body that is not a JSON object, whatever the media type")
  void refusesBodyThatIsNotAJsonObject(final String body, final String msgId) {
    Stream.concat(imageManifestTypes(), indexTypes())
        .forEach(
            type ->
                assertThatThrownBy(() -> DockerManifestValidator.validate(type, body))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(msgId));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("imageManifestCases")
  @DisplayName("refuses an image manifest without what the push reads")
  void refusesIncompleteImageManifest(final String body, final String msgId) {
    imageManifestTypes()
        .forEach(
            type ->
                assertThatThrownBy(() -> DockerManifestValidator.validate(type, body))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(msgId));
  }

  private static Stream<Arguments> imageManifestCases() {
    return Stream.of(
        Arguments.of("{}", "manifestConfigMissing"),
        Arguments.of("{\"layers\":[" + LAYER + "]}", "manifestConfigMissing"),
        Arguments.of("{\"config\":null,\"layers\":[" + LAYER + "]}", "manifestConfigMissing"),
        Arguments.of(image("{\"size\":2}", "[" + LAYER + "]"), "manifestConfigMissing"),
        Arguments.of(image("{\"digest\":null}", "[" + LAYER + "]"), "manifestConfigMissing"),
        Arguments.of(image("{\"digest\":\"  \"}", "[" + LAYER + "]"), "manifestConfigMissing"),
        Arguments.of("{\"config\":" + CONFIG + "}", "manifestLayersInvalid"),
        Arguments.of("{\"config\":" + CONFIG + ",\"layers\":null}", "manifestLayersInvalid"),
        Arguments.of(image(CONFIG, "[{\"size\":3}]"), "manifestLayersInvalid"),
        Arguments.of(image(CONFIG, "[{\"digest\":\"\"}]"), "manifestLayersInvalid"),
        Arguments.of(image(CONFIG, "[" + LAYER + ",null]"), "manifestLayersInvalid"),
        Arguments.of(image("\"cfg\"", "[" + LAYER + "]"), "manifestInvalid"),
        Arguments.of(image(CONFIG, "\"layers\""), "manifestInvalid"),
        Arguments.of(image(CONFIG, "{\"digest\":\"sha256:l\"}"), "manifestInvalid"),
        Arguments.of(image(CONFIG, "[\"sha256:l\"]"), "manifestInvalid"),
        Arguments.of(
            image(CONFIG, "[{\"digest\":\"sha256:l\",\"size\":\"big\"}]"), "manifestInvalid"),
        Arguments.of(
            image("{\"digest\":\"sha256:c\",\"size\":\"big\"}", "[" + LAYER + "]"),
            "manifestInvalid"),
        Arguments.of(
            "{\"schemaVersion\":\"two\",\"config\":" + CONFIG + ",\"layers\":[" + LAYER + "]}",
            "manifestInvalid"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("imageManifestTypes")
  @DisplayName("refuses schemaVersion 1 for a media type that requires 2 (RPS-1151)")
  void refusesSchemaVersion1ForSchema2MediaType(final String type) {
    assertThatThrownBy(
            () ->
                DockerManifestValidator.validate(
                    type, "{\"schemaVersion\":1,\"config\":" + CONFIG + ",\"layers\":[]}"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestSchemaVersionInvalid");
  }

  @Test
  @DisplayName("accepts schemaVersion 1 for the legacy Docker schema1 media type")
  void acceptsSchemaVersion1ForSchema1MediaType() {
    assertThatCode(
            () ->
                DockerManifestValidator.validate(
                    DOCKER_MANIFEST_SCHEMA1,
                    "{\"schemaVersion\":1,\"config\":" + CONFIG + ",\"layers\":[]}"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses schemaVersion 2 for the legacy Docker schema1 media type")
  void refusesSchemaVersion2ForSchema1MediaType() {
    assertThatThrownBy(
            () ->
                DockerManifestValidator.validate(
                    DOCKER_MANIFEST_SCHEMA1,
                    "{\"schemaVersion\":2,\"config\":" + CONFIG + ",\"layers\":[]}"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestSchemaVersionInvalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("imageManifestTypes")
  @DisplayName("refuses a schemaVersion too large to fit an int, instead of truncating it")
  void refusesOutOfRangeSchemaVersion(final String type) {
    assertThatThrownBy(
            () ->
                DockerManifestValidator.validate(
                    type, "{\"schemaVersion\":4294967298,\"config\":" + CONFIG + ",\"layers\":[]}"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestSchemaVersionInvalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("imageManifestTypes")
  @DisplayName("refuses a negative schemaVersion")
  void refusesNegativeSchemaVersion(final String type) {
    assertThatThrownBy(
            () ->
                DockerManifestValidator.validate(
                    type, "{\"schemaVersion\":-1,\"config\":" + CONFIG + ",\"layers\":[]}"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestSchemaVersionInvalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexCases")
  @DisplayName("refuses an index without what the push reads")
  void refusesIncompleteIndex(final String body, final String msgId) {
    indexTypes()
        .forEach(
            type ->
                assertThatThrownBy(() -> DockerManifestValidator.validate(type, body))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(msgId));
  }

  private static Stream<Arguments> indexCases() {
    return Stream.of(
        Arguments.of("{}", "manifestListManifestsInvalid"),
        Arguments.of(index("null"), "manifestListManifestsInvalid"),
        Arguments.of(image(CONFIG, "[" + LAYER + "]"), "manifestListManifestsInvalid"),
        Arguments.of(
            index("[{\"size\":9,\"platform\":" + PLATFORM + "}]"), "manifestListManifestsInvalid"),
        Arguments.of(
            index("[{\"digest\":\" \",\"size\":9,\"platform\":" + PLATFORM + "}]"),
            "manifestListManifestsInvalid"),
        Arguments.of(index("[" + ENTRY + ",null]"), "manifestListManifestsInvalid"),
        Arguments.of(
            index("[{\"digest\":\"sha256:m\",\"platform\":" + PLATFORM + "}]"), "manifestInvalid"),
        Arguments.of(index("\"manifests\""), "manifestInvalid"),
        Arguments.of(index("{\"digest\":\"sha256:m\"}"), "manifestInvalid"),
        Arguments.of(index("[\"sha256:m\"]"), "manifestInvalid"),
        Arguments.of(
            index("[{\"digest\":\"sha256:m\",\"size\":\"big\",\"platform\":" + PLATFORM + "}]"),
            "manifestInvalid"));
  }

  @ParameterizedTest
  @MethodSource("imageManifestTypes")
  @DisplayName("accepts a complete image manifest")
  void acceptsImageManifest(final String type) {
    assertThatCode(() -> DockerManifestValidator.validate(type, image(CONFIG, "[" + LAYER + "]")))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("imageManifestTypes")
  @DisplayName("accepts an image manifest without layers, which an empty image legitimately has")
  void acceptsImageManifestWithoutLayers(final String type) {
    assertThatCode(() -> DockerManifestValidator.validate(type, image(CONFIG, "[]")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("accepts an attestation manifest: an empty config descriptor and a subject")
  void acceptsAttestationManifest() {
    final var attestation =
        "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\","
            + "\"digest\":\"sha256:e\",\"size\":2},\"layers\":["
            + LAYER
            + "],"
            + "\"subject\":{\"digest\":\"sha256:s\",\"size\":5},\"annotations\":{\"a\":\"b\"}}";

    assertThatCode(() -> DockerManifestValidator.validate(OCI_MANIFEST, attestation))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("indexTypes")
  @DisplayName("accepts an index, with or without entries, and fields it does not read")
  void acceptsIndex(final String type) {
    assertThatCode(() -> DockerManifestValidator.validate(type, index("[" + ENTRY + "]")))
        .doesNotThrowAnyException();
    assertThatCode(() -> DockerManifestValidator.validate(type, index("[]")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                DockerManifestValidator.validate(
                    type,
                    index(
                        "[{\"digest\":\"sha256:m\",\"size\":9,\"platform\":{\"architecture\":\"arm64\","
                            + "\"os\":\"linux\",\"variant\":\"v8\"},\"annotations\":{\"k\":\"v\"},"
                            + "\"extra\":true}]")))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexTypes")
  @DisplayName(
      "accepts an index entry without a platform (RPS-1117): grouping an artifact and its"
          + " referrers, not per-platform images, is legitimate")
  void acceptsIndexEntryWithoutPlatform(final String type) {
    assertThatCode(
            () ->
                DockerManifestValidator.validate(
                    type, index("[{\"digest\":\"sha256:m\",\"size\":9}]")))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexTypes")
  @DisplayName("refuses an index schemaVersion other than 2 (RPS-1151)")
  void refusesWrongSchemaVersionForIndex(final String type) {
    assertThatThrownBy(
            () -> DockerManifestValidator.validate(type, "{\"schemaVersion\":1,\"manifests\":[]}"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestSchemaVersionInvalid");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/vnd.docker.distribution.manifest.v1+prettyjws",
        "application/json",
        "text/plain"
      })
  @DisplayName("refuses a manifest media type the registry does not store (RPS-1110)")
  void refusesUnknownMediaType(final String type) {
    assertThatThrownBy(() -> DockerManifestValidator.validate(type, "not json"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestMediaTypeUnsupported");
  }
}
