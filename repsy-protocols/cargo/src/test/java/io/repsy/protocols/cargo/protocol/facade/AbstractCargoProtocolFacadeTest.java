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
package io.repsy.protocols.cargo.protocol.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.facades.AbstractCargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoProtocolFacade")
class AbstractCargoProtocolFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "cargo";

  @Mock private CargoStorageService storageService;
  @Mock private CargoCrateService<UUID> crateService;
  @Mock private ObjectMapper objectMapper;
  @Mock private BaseRepoInfo<UUID> repoInfo;

  @Mock
  @SuppressWarnings("rawtypes")
  private BaseUrlParserProperties urlProps;

  @Mock private BaseUsages usages;
  @Mock private Resource resource;

  private AbstractCargoProtocolFacade<UUID> facade;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    facade = new TestFacade(storageService, crateService, objectMapper);
    lenient().when(urlProps.getRepoInfo()).thenReturn(repoInfo);
    lenient().when(repoInfo.getStorageKey()).thenReturn(REPO_ID);
    lenient().when(repoInfo.getName()).thenReturn(REPO_NAME);
  }

  // ── Concrete subclass for testing the abstract facade ────────────────────

  static class TestFacade extends AbstractCargoProtocolFacade<UUID> {

    TestFacade(final CargoStorageService s, final CargoCrateService<UUID> c, final ObjectMapper o) {
      super(s, c, o);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private ProtocolContext context(final String relativePath) {
    // lenient: publish() only reads repoInfo, not relativePath; other operations read both
    lenient().when(urlProps.getRelativePath()).thenReturn(new RelativePath(relativePath));
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

  static byte[] publishPayload(final String metadataJson, final byte[] crateBytes) {
    try {
      final var out = new ByteArrayOutputStream();
      final var json = metadataJson.getBytes(StandardCharsets.UTF_8);
      writeU32LE(out, json.length);
      out.write(json);
      writeU32LE(out, crateBytes.length);
      out.write(crateBytes);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void writeU32LE(final OutputStream out, final int value) throws IOException {
    final var buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(value);
    out.write(buf.array());
  }

  static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  static CratePublishRequest minimalRequest(final String name, final String vers) {
    return new CratePublishRequest(
        name, vers, null, null, List.of(), null, null, null, null, null, List.of(), List.of(), null,
        null, null, null, null, null, null);
  }

  static InputStream stream(final byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  // =========================================================================

  @Nested
  @DisplayName("publish()")
  class PublishTests {

    @BeforeEach
    void stubWriteSide() throws Exception {
      lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      lenient()
          .when(storageService.writeCrateAndIndex(any(), any(), any(), any(), any(), any()))
          .thenReturn(usages);
    }

    @Test
    @DisplayName("calls crate service and storage service on a valid request")
    void callsDownstreamServicesOnSuccess() throws Exception {
      final var crateBytes = "fake-crate-data".getBytes(StandardCharsets.UTF_8);
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(minimalRequest("my_crate", "1.0.0"));

      facade.publish(context("/api/v1/crates/new"), stream(publishPayload("{}", crateBytes)));

      verify(crateService).publish(eq(repoInfo), any(CratePublishRequest.class));
      verify(storageService)
          .writeCrateAndIndex(
              eq(REPO_ID), eq(REPO_NAME), eq("my_crate"), eq("1.0.0"), eq(crateBytes), any());
    }

    @Test
    @DisplayName("computes SHA-256 of crate bytes and overwrites any client-supplied cksum")
    void serverChecksumOverwritesClientValue() throws Exception {
      final var crateBytes = "deterministic-payload".getBytes(StandardCharsets.UTF_8);
      final var expectedCksum = sha256Hex(crateBytes);
      // Client deliberately sends an incorrect cksum — server must ignore and recompute.
      final var requestWithWrongCksum =
          new CratePublishRequest(
              "my_crate",
              "1.0.0",
              null,
              null,
              List.of(),
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              "client-wrong-cksum",
              null);
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(requestWithWrongCksum);

      facade.publish(context("/api/v1/crates/new"), stream(publishPayload("{}", crateBytes)));

      final var captor = ArgumentCaptor.forClass(CratePublishRequest.class);
      verify(crateService).publish(eq(repoInfo), captor.capture());
      assertThat(captor.getValue().cksum()).isEqualTo(expectedCksum);
    }

    @Test
    @DisplayName("normalises crate name to lowercase, replacing hyphens with underscores")
    void normalisesNameForStorage() throws Exception {
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(minimalRequest("My-Crate", "1.0.0"));

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      verify(storageService).writeCrateAndIndex(any(), any(), eq("my_crate"), any(), any(), any());
    }

    @Test
    @DisplayName("index entry always has yanked=false immediately after publish")
    void indexEntryYankedIsFalseOnPublish() throws Exception {
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(minimalRequest("my_crate", "1.0.0"));

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      assertThat(((CrateIndexEntry) captor.getValue()).yanked()).isFalse();
    }

    @Test
    @DisplayName("sets v=1 in index entry when features2 is absent")
    void setsV1WhenNoFeatures2() throws Exception {
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(minimalRequest("my_crate", "1.0.0")); // features2 = null

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      assertThat(((CrateIndexEntry) captor.getValue()).v()).isEqualTo(1);
    }

    @Test
    @DisplayName("sets v=2 in index entry when features2 is present")
    void setsV2WhenFeatures2Present() throws Exception {
      final var requestWithFeatures2 =
          new CratePublishRequest(
              "my_crate",
              "1.0.0",
              null,
              null,
              List.of(),
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null,
              Map.of("feat_a", List.of("+dep_a")));
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(requestWithFeatures2);

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      assertThat(((CrateIndexEntry) captor.getValue()).v()).isEqualTo(2);
    }

    @Test
    @DisplayName("maps dep with explicitNameInToml: uses it as name, original as packageName")
    void depWithExplicitNameInToml() throws Exception {
      final var dep =
          new CratePublishDep(
              "serde", "^1", List.of(), false, true, null, "normal", null, "my_serde");
      final var request =
          new CratePublishRequest(
              "my_crate",
              "1.0.0",
              List.of(dep),
              null,
              List.of(),
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null,
              null);
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(request);

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      final var entry = (CrateIndexEntry) captor.getValue();
      assertThat(entry.deps()).hasSize(1);
      assertThat(entry.deps().get(0).name()).isEqualTo("my_serde");
      assertThat(entry.deps().get(0).packageName()).isEqualTo("serde");
    }

    @Test
    @DisplayName("maps dep without explicitNameInToml: uses name directly, packageName is null")
    void depWithoutExplicitNameInToml() throws Exception {
      final var dep =
          new CratePublishDep("serde", "^1", List.of(), false, true, null, "normal", null, null);
      final var request =
          new CratePublishRequest(
              "my_crate",
              "1.0.0",
              List.of(dep),
              null,
              List.of(),
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null,
              null);
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(request);

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      final var entry = (CrateIndexEntry) captor.getValue();
      assertThat(entry.deps()).hasSize(1);
      assertThat(entry.deps().get(0).name()).isEqualTo("serde");
      assertThat(entry.deps().get(0).packageName()).isNull();
    }

    @Test
    @DisplayName("index entry has empty deps list when request deps is null")
    void indexEntryHasEmptyDepsWhenRequestDepsNull() throws Exception {
      when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
          .thenReturn(minimalRequest("my_crate", "1.0.0")); // deps = null

      facade.publish(
          context("/api/v1/crates/new"),
          stream(publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8))));

      final var captor = ArgumentCaptor.forClass(Object.class);
      verify(objectMapper).writeValueAsString(captor.capture());
      assertThat(((CrateIndexEntry) captor.getValue()).deps()).isEmpty();
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class ValidationTests {

      @Test
      @DisplayName("throws when crate name is blank")
      void throwsOnBlankName() throws Exception {
        assertPublishThrows(minimalRequest("", "1.0.0"), "crate name cannot be empty");
      }

      @Test
      @DisplayName("throws when crate name exceeds 64 characters")
      void throwsOnNameTooLong() throws Exception {
        assertPublishThrows(
            minimalRequest("a".repeat(65), "1.0.0"), "must be at most 64 characters");
      }

      @Test
      @DisplayName("throws when crate name starts with underscore")
      void throwsOnNameStartingWithUnderscore() throws Exception {
        assertPublishThrows(minimalRequest("_foo", "1.0.0"), "must start with");
      }

      @Test
      @DisplayName("throws when crate name starts with hyphen")
      void throwsOnNameStartingWithHyphen() throws Exception {
        assertPublishThrows(minimalRequest("-foo", "1.0.0"), "must start with");
      }

      @Test
      @DisplayName("throws when crate name contains a dot")
      void throwsOnNameWithDot() throws Exception {
        assertPublishThrows(minimalRequest("foo.bar", "1.0.0"), "must start with");
      }

      @Test
      @DisplayName("accepts name with hyphens and underscores after the first character")
      void acceptsNameWithHyphensAndUnderscores() throws Exception {
        assertPublishSucceeds(minimalRequest("my-crate_v2", "1.0.0"));
      }

      @Test
      @DisplayName("accepts a 64-character name at the exact boundary")
      void accepts64CharName() throws Exception {
        assertPublishSucceeds(minimalRequest("a".repeat(64), "1.0.0"));
      }

      @Test
      @DisplayName("throws when version is blank")
      void throwsOnBlankVersion() throws Exception {
        assertPublishThrows(minimalRequest("my_crate", ""), "version cannot be empty");
      }

      @Test
      @DisplayName("throws when version has only major.minor (missing patch)")
      void throwsOnMissingPatch() throws Exception {
        assertPublishThrows(minimalRequest("my_crate", "1.0"), "not a valid semver");
      }

      @Test
      @DisplayName("throws when version is a plain integer")
      void throwsOnPlainInteger() throws Exception {
        assertPublishThrows(minimalRequest("my_crate", "1"), "not a valid semver");
      }

      @Test
      @DisplayName("accepts a pre-release suffix like 1.0.0-alpha.1")
      void acceptsPreReleaseSuffix() throws Exception {
        assertPublishSucceeds(minimalRequest("my_crate", "1.0.0-alpha.1"));
      }

      @Test
      @DisplayName("accepts a build-metadata suffix like 1.0.0+build.1")
      void acceptsBuildMetadata() throws Exception {
        assertPublishSucceeds(minimalRequest("my_crate", "1.0.0+build.1"));
      }

      @Test
      @DisplayName("throws when there are more than 5 keywords")
      void throwsOnTooManyKeywords() throws Exception {
        final var request =
            new CratePublishRequest(
                "my_crate",
                "1.0.0",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of("k1", "k2", "k3", "k4", "k5", "k6"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertPublishThrows(request, "at most 5 keywords");
      }

      @Test
      @DisplayName("accepts exactly 5 keywords at the boundary")
      void accepts5Keywords() throws Exception {
        final var request =
            new CratePublishRequest(
                "my_crate",
                "1.0.0",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of("k1", "k2", "k3", "k4", "k5"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertPublishSucceeds(request);
      }

      @Test
      @DisplayName("throws when a keyword exceeds 20 characters")
      void throwsOnKeywordTooLong() throws Exception {
        final var request =
            new CratePublishRequest(
                "my_crate",
                "1.0.0",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of("a".repeat(21)),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertPublishThrows(request, "at most 20 characters");
      }

      @Test
      @DisplayName("accepts a 20-character keyword at the exact boundary")
      void accepts20CharKeyword() throws Exception {
        final var request =
            new CratePublishRequest(
                "my_crate",
                "1.0.0",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of("a".repeat(20)),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertPublishSucceeds(request);
      }

      private void assertPublishThrows(final CratePublishRequest request, final String messagePart)
          throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
            .thenReturn(request);
        final var payload = publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> facade.publish(context("/api/v1/crates/new"), stream(payload)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(messagePart);
      }

      private void assertPublishSucceeds(final CratePublishRequest request) throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(CratePublishRequest.class)))
            .thenReturn(request);
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        lenient()
            .when(storageService.writeCrateAndIndex(any(), any(), any(), any(), any(), any()))
            .thenReturn(usages);
        final var payload = publishPayload("{}", "bytes".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> facade.publish(context("/api/v1/crates/new"), stream(payload)))
            .doesNotThrowAnyException();
      }
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("yank()")
  class YankTests {

    @Test
    @DisplayName("delegates to crate service with normalised name and extracted version")
    void yanksWithNormalisedNameAndVersion() {
      facade.yank(context("/api/v1/crates/my_crate/1.2.3/yank"));
      verify(crateService).yank(repoInfo, "my_crate", "1.2.3");
    }

    @Test
    @DisplayName("normalises hyphens to underscores in crate name on yank")
    void normalisesHyphenToUnderscoreOnYank() {
      facade.yank(context("/api/v1/crates/my-crate/2.0.0/yank"));
      verify(crateService).yank(repoInfo, "my_crate", "2.0.0");
    }

    @Test
    @DisplayName("normalises uppercase crate name to lowercase on yank")
    void normalisesUppercaseOnYank() {
      facade.yank(context("/api/v1/crates/MY_CRATE/1.0.0/yank"));
      verify(crateService).yank(repoInfo, "my_crate", "1.0.0");
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("unyank()")
  class UnyankTests {

    @Test
    @DisplayName("delegates to crate service with normalised name and extracted version")
    void unyanksWithNormalisedNameAndVersion() {
      facade.unyank(context("/api/v1/crates/my_crate/3.0.0/unyank"));
      verify(crateService).unyank(repoInfo, "my_crate", "3.0.0");
    }

    @Test
    @DisplayName("normalises hyphens to underscores in crate name on unyank")
    void normalisesHyphenToUnderscoreOnUnyank() {
      facade.unyank(context("/api/v1/crates/my-crate/1.0.0/unyank"));
      verify(crateService).unyank(repoInfo, "my_crate", "1.0.0");
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("getIndexEntries()")
  class GetIndexEntriesTests {

    @Test
    @DisplayName("delegates to crate service using the last path segment as crate name")
    void delegatesToCrateServiceWithLastSegment() {
      final var entries = List.<CrateIndexEntry>of();
      when(crateService.getIndexEntries(repoInfo, "repsy_e2e_test")).thenReturn(entries);

      final var result = facade.getIndexEntries(context("/re/ps/repsy_e2e_test"));

      assertThat(result).isSameAs(entries);
      verify(crateService).getIndexEntries(repoInfo, "repsy_e2e_test");
    }

    @Test
    @DisplayName("extracts only the last segment for 2-char prefix paths")
    void extractsLastSegmentForShortPaths() {
      final var entries = List.<CrateIndexEntry>of();
      when(crateService.getIndexEntries(repoInfo, "ab")).thenReturn(entries);

      facade.getIndexEntries(context("/2/ab"));

      verify(crateService).getIndexEntries(repoInfo, "ab");
    }

    @Test
    @DisplayName("returns whatever the crate service returns")
    void returnsCrateServiceResult() {
      final var entries =
          List.of(
              new CrateIndexEntry(
                  "my_crate", "1.0.0", List.of(), "abc123", Map.of(), false, null, 1, null, null));
      when(crateService.getIndexEntries(repoInfo, "my_crate")).thenReturn(entries);

      final var result = facade.getIndexEntries(context("/my/cr/my_crate"));

      assertThat(result).isSameAs(entries);
    }
  }

  // =========================================================================

  @Nested
  @DisplayName("download()")
  class DownloadTests {

    @Test
    @DisplayName("returns the resource from storage and increments the download count")
    void downloadsAndIncrementsCount() {
      when(storageService.getCrate(REPO_ID, REPO_NAME, "my_crate", "1.0.0")).thenReturn(resource);

      final var result = facade.download(context("/api/v1/crates/my_crate/1.0.0/download"));

      assertThat(result).isSameAs(resource);
      verify(crateService).incrementDownloadCount(repoInfo, "my_crate", "1.0.0");
    }

    @Test
    @DisplayName("normalises hyphenated crate name before passing to storage")
    void normalisesNameInDownloadPath() {
      when(storageService.getCrate(REPO_ID, REPO_NAME, "my_crate", "2.0.0")).thenReturn(resource);

      facade.download(context("/api/v1/crates/My-Crate/2.0.0/download"));

      verify(storageService).getCrate(REPO_ID, REPO_NAME, "my_crate", "2.0.0");
    }

    @Test
    @DisplayName("passes correct version to incrementDownloadCount")
    void incrementsCountWithCorrectVersion() {
      when(storageService.getCrate(any(), any(), any(), eq("0.5.1"))).thenReturn(resource);

      facade.download(context("/api/v1/crates/some_crate/0.5.1/download"));

      verify(crateService).incrementDownloadCount(repoInfo, "some_crate", "0.5.1");
    }
  }
}
