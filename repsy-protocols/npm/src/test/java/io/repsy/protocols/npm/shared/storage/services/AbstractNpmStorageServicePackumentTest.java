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
package io.repsy.protocols.npm.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.util.Pair;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What the packument keeps and serves: the abbreviated document carries what a client decides on
 * before it has the tarball (RPS-1356), the publish-only fields of a publish are not kept or served
 * (RPS-1357), and an empty deprecation is no deprecation (RPS-1360).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService packument (RPS-1356, RPS-1357, RPS-1360)")
@SuppressWarnings("unchecked")
class AbstractNpmStorageServicePackumentTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final Path BASE_PATH = Path.of("demo");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Supplier<NpmPackageSnapshot> NO_ROWS =
      () -> {
        throw new AssertionError("the file is there, the rows are not needed");
      };

  @Mock private StorageStrategy storageStrategy;

  private AbstractNpmStorageService service;
  private final Map<String, byte[]> written = new LinkedHashMap<>();

  static class TestStorageService extends AbstractNpmStorageService {

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  @BeforeEach
  void setUp() {
    this.service = new TestStorageService(this.storageStrategy);
  }

  private void stored(final String json) {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8))));
  }

  private void recordWrites() {
    when(this.storageStrategy.write(anyString(), any(StoragePath.class), any(InputStream.class)))
        .thenAnswer(
            invocation -> {
              final var out = new ByteArrayOutputStream();
              invocation.<InputStream>getArgument(2).transferTo(out);
              this.written.put(invocation.<StoragePath>getArgument(1).getPath(), out.toByteArray());

              return BaseUsages.ofDisk(out.size());
            });
  }

  private Map<String, Object> writtenPackument() {
    final var bytes =
        this.written.entrySet().stream()
            .filter(entry -> entry.getKey().endsWith("package.json"))
            .findFirst()
            .orElseThrow()
            .getValue();

    return MAPPER.readValue(bytes, new TypeReference<>() {});
  }

  private static Map<String, Object> versionOf(
      final Map<String, Object> packument, final String version) {

    return (Map<String, Object>) ((Map<String, Object>) packument.get("versions")).get(version);
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1356
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the abbreviated document keeps os, cpu, libc, peerDependenciesMeta and the like")
  void abbreviatedKeepsTheInstallFields() {
    final var version = new LinkedHashMap<String, Object>();
    version.put("name", "demo");
    version.put("version", "1.0.0");
    version.put("dist", Map.of("tarball", "t"));
    version.put("os", List.of("linux", "!win32"));
    version.put("cpu", List.of("x64"));
    version.put("libc", List.of("glibc"));
    version.put("peerDependenciesMeta", Map.of("react", Map.of("optional", true)));
    version.put("hasInstallScript", true);
    version.put("funding", Map.of("url", "https://example.test/fund"));
    version.put("acceptDependencies", Map.of("left-pad", "^1"));
    version.put("readme", "not part of the abbreviation");

    final var abbreviated =
        this.service.createAbbreviatedMetadata(
            Map.of(
                "name",
                "demo",
                "dist-tags",
                Map.of("latest", "1.0.0"),
                "time",
                Map.of("modified", "2026-01-01T00:00:00.000Z"),
                "versions",
                Map.of("1.0.0", version)));

    final var result = versionOf(abbreviated, "1.0.0");
    assertThat(result)
        .containsEntry("os", List.of("linux", "!win32"))
        .containsEntry("cpu", List.of("x64"))
        .containsEntry("libc", List.of("glibc"))
        .containsEntry("peerDependenciesMeta", Map.of("react", Map.of("optional", true)))
        .containsEntry("hasInstallScript", true)
        .containsEntry("funding", Map.of("url", "https://example.test/fund"))
        .containsEntry("acceptDependencies", Map.of("left-pad", "^1"))
        .doesNotContainKey("readme");
  }

  @Test
  @DisplayName("a version without those fields gets none of them in the abbreviated document")
  void abbreviatedDoesNotInventThem() {
    final var abbreviated =
        this.service.createAbbreviatedMetadata(
            Map.of(
                "name",
                "demo",
                "dist-tags",
                Map.of("latest", "1.0.0"),
                "versions",
                Map.of("1.0.0", Map.of("name", "demo", "version", "1.0.0"))));

    assertThat(versionOf(abbreviated, "1.0.0"))
        .doesNotContainKeys(
            "os",
            "cpu",
            "libc",
            "peerDependenciesMeta",
            "hasInstallScript",
            "funding",
            "acceptDependencies");
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1357
  // -------------------------------------------------------------------------------------------

  private static final String LEGACY =
      "{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"time\":{\"modified\":\"m\"},"
          + "\"_attachments\":{\"demo-1.0.0.tgz\":{\"data\":\"AAAA\",\"length\":3}},"
          + "\"_from\":\"file:/home/ada/demo-1.0.0.tgz\",\"_resolved\":\"/home/ada/demo-1.0.0.tgz\","
          + "\"versions\":{\"1.0.0\":{\"name\":\"demo\",\"version\":\"1.0.0\","
          + "\"_from\":\"file:/home/ada/demo-1.0.0.tgz\","
          + "\"_resolved\":\"/home/ada/demo-1.0.0.tgz\",\"dist\":{\"tarball\":\"t\"}}}}";

  @Test
  @DisplayName("a packument stored with the publish's own fields is served without them")
  void servedWithoutThePublishOnlyFields() throws Exception {
    this.stored(LEGACY);

    final var packument =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false, NO_ROWS);

    assertThat(packument).doesNotContainKeys("_attachments", "_from", "_resolved");
    assertThat(versionOf(packument, "1.0.0"))
        .doesNotContainKeys("_from", "_resolved")
        .containsEntry("name", "demo");
  }

  @Test
  @DisplayName("the read that has no rows to fall back on serves it without them too")
  void servedWithoutThemWhenReadWithoutRows() throws Exception {
    this.stored(LEGACY);

    final var packument = this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false);

    assertThat(packument).doesNotContainKeys("_attachments", "_from", "_resolved");
    assertThat(versionOf(packument, "1.0.0")).doesNotContainKeys("_from", "_resolved");
  }

  @Test
  @DisplayName("a publish writes the tarball on its own and the packument without its copy")
  void publishKeepsNoAttachmentInThePackument() throws Exception {
    this.recordWrites();
    final var tarball = "the tarball".getBytes(StandardCharsets.UTF_8);
    final var payload =
        MAPPER.readValue(
            "{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},"
                + "\"_from\":\"file:/home/ada/demo-1.0.0.tgz\","
                + "\"versions\":{\"1.0.0\":{\"name\":\"demo\",\"version\":\"1.0.0\","
                + "\"_resolved\":\"/home/ada/demo-1.0.0.tgz\",\"dist\":{\"tarball\":\"t\"}}},"
                + "\"_attachments\":{\"demo-1.0.0.tgz\":{\"data\":\""
                + Base64.getEncoder().encodeToString(tarball)
                + "\",\"length\":"
                + tarball.length
                + "}}}",
            new TypeReference<LinkedHashMap<String, Object>>() {});

    final var usages =
        this.service.writeTarballAndMetadata(
            REPO_ID, REPO_NAME, payload, BASE_PATH, "demo", "1.0.0");

    final var packument = this.writtenPackument();
    assertThat(packument).doesNotContainKeys("_attachments", "_from", "_resolved");
    assertThat(versionOf(packument, "1.0.0")).doesNotContainKeys("_from", "_resolved");
    assertThat(versionOf(packument, "1.0.0").get("dist"))
        .isInstanceOfSatisfying(
            Map.class, dist -> assertThat(dist).containsKeys("shasum", "integrity"));
    assertThat(this.written)
        .anySatisfy(
            (path, bytes) -> {
              assertThat(path).endsWith("demo-1.0.0.tgz");
              assertThat(bytes).isEqualTo(tarball);
            });
    assertThat(usages.getDiskUsage())
        .as("the disk usage is the tarball and the packument, not a base64 copy of the tarball")
        .isEqualTo(this.written.values().stream().mapToLong(bytes -> bytes.length).sum());
  }

  // -------------------------------------------------------------------------------------------
  // RPS-1360
  // -------------------------------------------------------------------------------------------

  private static final String DEPRECATED =
      "{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.2.0\"},\"time\":{\"modified\":\"m\"},"
          + "\"versions\":{"
          + "\"1.0.0\":{\"name\":\"demo\",\"version\":\"1.0.0\",\"deprecated\":\"old\"},"
          + "\"1.1.0\":{\"name\":\"demo\",\"version\":\"1.1.0\",\"deprecated\":\"older\"},"
          + "\"1.2.0\":{\"name\":\"demo\",\"version\":\"1.2.0\"}}}";

  @Test
  @DisplayName("deprecating with a message sets it, with an empty one removes the field")
  void undeprecatingRemovesTheField() throws Exception {
    this.stored(DEPRECATED);
    this.recordWrites();

    this.service.deprecateVersions(
        REPO_ID,
        REPO_NAME,
        BASE_PATH,
        new ArrayList<>(List.of(Pair.of("1.0.0", ""), Pair.of("1.2.0", "use 1.3"))));

    final var packument = this.writtenPackument();
    assertThat(versionOf(packument, "1.0.0")).doesNotContainKey("deprecated");
    assertThat(versionOf(packument, "1.1.0")).containsEntry("deprecated", "older");
    assertThat(versionOf(packument, "1.2.0")).containsEntry("deprecated", "use 1.3");
  }

  @Test
  @DisplayName("an undeprecated version is not served as deprecated, however it was stored")
  void emptyDeprecationIsNotServed() throws Exception {
    this.stored(DEPRECATED.replace("\"deprecated\":\"old\"", "\"deprecated\":\"\""));

    final var full = this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false, NO_ROWS);
    final var abbreviated =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", true, NO_ROWS);

    assertThat(versionOf(full, "1.0.0")).doesNotContainKey("deprecated");
    assertThat(versionOf(abbreviated, "1.0.0")).doesNotContainKey("deprecated");
    assertThat(versionOf(full, "1.1.0")).containsEntry("deprecated", "older");
    assertThat(versionOf(abbreviated, "1.1.0")).containsEntry("deprecated", "older");
  }
}
