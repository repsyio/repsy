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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1333: {@code dist.tarball} is the registry's own address, computed when a version is
 * published and again on every read, not what the publisher happened to send.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService dist.tarball (RPS-1333)")
@SuppressWarnings("unchecked")
class AbstractNpmStorageServiceTarballUrlTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final String PUBLISHER_URL =
      "http://publisher.internal:9090/npm-repo/demo/-/demo-1.0.0.tgz";
  private static final String STORED =
      "{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"time\":{\"modified\":\"m\"},"
          + "\"versions\":{\"1.0.0\":{\"name\":\"demo\",\"version\":\"1.0.0\","
          + "\"dist\":{\"tarball\":\""
          + PUBLISHER_URL
          + "\",\"shasum\":\"abc\"}}}}";
  private static final Supplier<NpmPackageSnapshot> NO_ROWS =
      () -> {
        throw new AssertionError("the file is there, the rows are not needed");
      };
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private StorageStrategy storageStrategy;

  private TestStorageService service;

  static class TestStorageService extends AbstractNpmStorageService {

    @Nullable String registryBaseUrl;

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }

    @Override
    protected @Nullable String registryBaseUrl() {
      return this.registryBaseUrl;
    }
  }

  @BeforeEach
  void setUp() {
    this.service = new TestStorageService(this.storageStrategy);
  }

  private void metadataIsStored() {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(STORED.getBytes(StandardCharsets.UTF_8))));
  }

  private static String tarballOf(final Map<String, Object> packument) {
    final var versions = (Map<String, Object>) packument.get("versions");
    final var version = (Map<String, Object>) versions.get("1.0.0");

    return ((Map<String, String>) version.get("dist")).get("tarball");
  }

  private Map<String, Object> publishPayload(final String tarball) {
    return MAPPER.readValue(
        "{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},"
            + "\"versions\":{\"1.0.0\":{\"name\":\"demo\",\"version\":\"1.0.0\","
            + "\"dist\":{\"tarball\":\""
            + tarball
            + "\"}}},\"_attachments\":{\"demo-1.0.0.tgz\":{\"data\":\"AAAA\",\"length\":3}}}",
        new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  @Test
  @DisplayName("a full packument names the registry's address, not the publisher's")
  void fullPackumentIsRewritten() throws Exception {
    this.metadataIsStored();
    this.service.registryBaseUrl = "https://repo.example.test";

    final var packument =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false, NO_ROWS);

    assertThat(tarballOf(packument))
        .isEqualTo("https://repo.example.test/npm-repo/demo/-/demo-1.0.0.tgz");
  }

  @Test
  @DisplayName("an abbreviated packument names the registry's address too")
  void abbreviatedPackumentIsRewritten() throws Exception {
    this.metadataIsStored();
    this.service.registryBaseUrl = "https://repo.example.test/prefix/";

    final var packument = this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", true, NO_ROWS);

    assertThat(tarballOf(packument))
        .isEqualTo("https://repo.example.test/prefix/npm-repo/demo/-/demo-1.0.0.tgz");
  }

  @Test
  @DisplayName("the read that has no rows to fall back on is rewritten as well")
  void readWithoutRowsIsRewritten() throws Exception {
    this.metadataIsStored();
    this.service.registryBaseUrl = "https://repo.example.test";

    final var packument = this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false);

    assertThat(tarballOf(packument))
        .isEqualTo("https://repo.example.test/npm-repo/demo/-/demo-1.0.0.tgz");
  }

  @Test
  @DisplayName("a registry that does not know its address serves what is stored")
  void unknownAddressLeavesTheStoredUrl() throws Exception {
    this.metadataIsStored();

    final var packument =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, "demo", false, NO_ROWS);

    assertThat(tarballOf(packument)).isEqualTo(PUBLISHER_URL);
  }

  @Test
  @DisplayName("a metadata read for a change does not rewrite what will be written back")
  void changeReadKeepsTheStoredUrl() throws Exception {
    this.metadataIsStored();
    this.service.registryBaseUrl = "https://repo.example.test";

    final var stored =
        this.service.getMetadata(StoragePath.of(REPO_ID, "demo/package.json"), REPO_NAME);

    assertThat(tarballOf(stored)).isEqualTo(PUBLISHER_URL);
  }

  @Test
  @DisplayName("a published version gets the registry's address, whatever scheme the client wrote")
  void publishComputesTheUrl() throws Exception {
    this.service.registryBaseUrl = "https://repo.example.test";
    final var payload =
        this.publishPayload("http://publisher.internal:9090/x/demo/-/demo-1.0.0.tgz");

    this.service.processPackagePayload(payload, REPO_NAME);

    assertThat(tarballOf(payload))
        .isEqualTo("https://repo.example.test/npm-repo/demo/-/demo-1.0.0.tgz");
  }

  @Test
  @DisplayName("a published scoped version gets the scope in the path and none in the file name")
  void publishComputesTheScopedUrl() throws Exception {
    this.service.registryBaseUrl = "https://repo.example.test";
    final var payload =
        MAPPER.readValue(
            "{\"name\":\"@foo/demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},"
                + "\"versions\":{\"1.0.0\":{\"name\":\"@foo/demo\",\"version\":\"1.0.0\","
                + "\"dist\":{\"tarball\":\"http://h/npm-repo/@foo/demo/-/@foo/demo-1.0.0.tgz\"}}},"
                + "\"_attachments\":{\"demo-1.0.0.tgz\":{\"data\":\"AAAA\",\"length\":3}}}",
            new TypeReference<LinkedHashMap<String, Object>>() {});

    this.service.processPackagePayload(payload, REPO_NAME);

    assertThat(tarballOf(payload))
        .isEqualTo("https://repo.example.test/npm-repo/@foo/demo/-/demo-1.0.0.tgz");
  }

  @Test
  @DisplayName("without an address of its own the registry keeps the client's URL at publish")
  void publishFallsBackToTheClientsUrl() throws Exception {
    final var payload = this.publishPayload(PUBLISHER_URL);

    this.service.processPackagePayload(payload, REPO_NAME);

    assertThat(tarballOf(payload)).isEqualTo(PUBLISHER_URL);
  }
}
