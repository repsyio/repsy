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

import io.repsy.protocols.npm.shared.constants.NpmConstants;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/**
 * Pins the payload, name and packument helpers of the npm utils classes that the other tests only
 * reach through mocks (RPS-2061), so that moving them by concern changes no behaviour.
 */
@DisplayName("NpmPackageUtils payload and packument helpers")
@SuppressWarnings("unchecked")
class NpmPackageUtilsPayloadTest {

  private static Map<String, Object> payload() {
    final var version = new HashMap<String, Object>();
    version.put("name", "demo");
    version.put("version", "1.2.3");
    version.put("dist", new HashMap<String, Object>());

    final var versions = new LinkedHashMap<String, Object>();
    versions.put("1.2.3", version);

    final var attachment = new HashMap<String, Object>();
    attachment.put("data", Base64.encodeBase64String("tarball".getBytes()));
    attachment.put("length", 7);

    final var payload = new HashMap<String, Object>();
    payload.put("name", "demo");
    payload.put("versions", versions);
    payload.put("dist-tags", new LinkedHashMap<>(Map.of("beta", "1.2.3")));
    payload.put("_attachments", new HashMap<>(Map.of("demo-1.2.3.tgz", attachment)));
    payload.put("time", new HashMap<>(Map.of("modified", "old")));

    return payload;
  }

  @Test
  @DisplayName("names: file name, full name and latest version")
  void names() {
    assertThat(NpmPackageUtils.getTarballFilename("demo", "1.0.0")).isEqualTo("demo-1.0.0.tgz");
    assertThat(NpmPackageUtils.buildFullName("foo", "demo")).isEqualTo("@foo/demo");
    assertThat(NpmPackageUtils.getLatestVersion(payload())).isNull();
    assertThat(NpmPackageUtils.getLatestVersion(Map.of("dist-tags", Map.of("latest", "2.0.0"))))
        .isEqualTo("2.0.0");
    assertThat(NpmPackageUtils.getFormattedCurrentTime())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
  }

  @Test
  @DisplayName("extracts the first version, dist-tag, version name and tarball data")
  void extractsFromPayload() {
    final var payload = payload();

    final var pair = NpmPayloadUtils.extractVersionFromPayload(payload);
    assertThat(pair.getFirst()).isEqualTo("1.2.3");
    assertThat(pair.getSecond()).containsEntry("name", "demo");
    assertThat(NpmPayloadUtils.extractVersionNameFromPayload(payload)).isEqualTo("1.2.3");

    final var tag = NpmPayloadUtils.extractFirstDistTagFromPayload(payload);
    assertThat(tag.getKey()).isEqualTo("beta");
    assertThat(tag.getValue()).isEqualTo("1.2.3");

    assertThat(NpmPayloadUtils.extractTarballDataFromPayload(payload))
        .isEqualTo(Base64.encodeBase64String("tarball".getBytes()));
  }

  @Test
  @DisplayName("the tarball length is the declared one, else the decoded data")
  void tarballLength() {
    final var payload = payload();
    assertThat(NpmPayloadUtils.getTarballLength(payload)).isEqualTo(7);

    final var attachments = (Map<String, Map<String, Object>>) payload.get("_attachments");
    attachments.values().iterator().next().remove("length");
    assertThat(NpmPayloadUtils.getTarballLength(payload)).isEqualTo(7);
  }

  @Test
  @DisplayName("the metadata length leaves out the attachments and keeps them in the map")
  void metadataLength() throws Exception {
    final var payload = payload();
    final var attachments = payload.get("_attachments");

    final var length = NpmPayloadUtils.getMetadataLength(payload);

    assertThat(payload.get("_attachments")).isSameAs(attachments);
    payload.remove("_attachments");
    assertThat(length)
        .isEqualTo(new tools.jackson.databind.ObjectMapper().writeValueAsBytes(payload).length);
  }

  @Test
  @DisplayName("updateDistFields sets the sha1 and the sha512 integrity of the tarball")
  void updateDistFields() {
    final var payload = payload();
    final var bytes = "tarball".getBytes();

    NpmPayloadUtils.updateDistFields(payload, "1.2.3", bytes);

    final var versions = (Map<String, Map<String, Object>>) payload.get("versions");
    final var dist = (Map<String, Object>) versions.get("1.2.3").get("dist");
    assertThat(dist.get("shasum")).isEqualTo(DigestUtils.sha1Hex(bytes));
    assertThat(dist.get("integrity"))
        .isEqualTo(
            "sha512-" + Base64.encodeBase64String(DigestUtils.getSha512Digest().digest(bytes)));
  }

  @Test
  @DisplayName("updateModifiedTime touches time.modified only when there is a time")
  void updateModifiedTime() {
    final var payload = payload();

    NpmMetadataUtils.updateModifiedTime(payload);

    assertThat(((Map<String, String>) payload.get("time")).get("modified")).isNotEqualTo("old");

    final var noTime = new HashMap<String, Object>();
    NpmMetadataUtils.updateModifiedTime(noTime);
    assertThat(noTime).isEmpty();
  }

  @Test
  @DisplayName("removeAllTagsPointingToVersion drops every tag of the version, keeps others")
  void removesTags() {
    final var metadata = new HashMap<String, Object>();
    metadata.put(
        NpmConstants.DIST_TAGS,
        new HashMap<>(Map.of("latest", "1.0.0", "next", "1.0.0", "old", "0.9.0")));

    NpmMetadataUtils.removeAllTagsPointingToVersion(metadata, "1.0.0");

    assertThat((Map<String, String>) metadata.get(NpmConstants.DIST_TAGS)).containsOnlyKeys("old");
  }

  @Test
  @DisplayName("isMetadataHasDeprecatedVersions is true for a non-null deprecated")
  void hasDeprecated() {
    final var plain = Map.<String, Object>of("versions", Map.of("1.0.0", Map.of("name", "demo")));
    final var deprecated =
        Map.<String, Object>of("versions", Map.of("1.0.0", Map.of("deprecated", "no")));

    assertThat(NpmPayloadUtils.isMetadataHasDeprecatedVersions(plain)).isFalse();
    assertThat(NpmPayloadUtils.isMetadataHasDeprecatedVersions(deprecated)).isTrue();
  }

  @Test
  @DisplayName("readMetadataFromResource parses the JSON object of a resource")
  void readsResource() throws IOException {
    final var resource = new ByteArrayResource("{\"name\":\"demo\",\"n\":[1]}".getBytes());

    assertThat(NpmMetadataUtils.readMetadataFromResource(resource))
        .containsEntry("name", "demo")
        .containsEntry("n", List.of(1));
  }
}
