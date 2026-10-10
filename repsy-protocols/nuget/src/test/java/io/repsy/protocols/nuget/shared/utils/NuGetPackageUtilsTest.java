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
package io.repsy.protocols.nuget.shared.utils;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NuGetPackageUtilsTest {

  @TempDir Path tempDir;

  @ParameterizedTest
  @CsvSource({
    "/v3/package/Some.Package/1.0.0, Some.Package, 1.0.0",
    "/v3/package/Some.Package/1.0.0/, Some.Package, 1.0.0",
    "/v3/package/Some.Package/, Some.Package, ''",
    "/v3/package/Some.Package, Some.Package, ''",
    "/v3/package, '', ''"
  })
  @DisplayName("extracts the package id and version from the request path")
  void extractsPackageIdAndVersion(
      final String path, final String expectedId, final String expectedVersion) {
    final var ctx = context(path);

    assertThat(NuGetPackageUtils.extractPackageId(ctx)).isEqualTo(expectedId);
    final var idAndVersion = NuGetPackageUtils.extractPackageIdAndVersion(ctx);
    assertThat(idAndVersion.id()).isEqualTo(expectedId);
    assertThat(idAndVersion.version()).isEqualTo(expectedVersion);
  }

  @Nested
  @DisplayName("the request parameters, the stream copy and the repo gate")
  class RequestAndPublishHelpers {

    @Test
    @DisplayName("parseNonNegativeParam falls back to the default for an absent value")
    void absentParamIsTheDefault() {
      assertThat(NuGetPackageUtils.parseNonNegativeParam(null, 20, "take")).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "7, 7", "2147483647, 2147483647"})
    @DisplayName("parseNonNegativeParam reads a non-negative integer")
    void readsNonNegativeInteger(final String value, final int expected) {
      assertThat(NuGetPackageUtils.parseNonNegativeParam(value, 20, "take")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "", " 1", "2147483648", "99999999999"})
    @DisplayName("parseNonNegativeParam names the parameter for a value that is not an int")
    void rejectsNonInteger(final String value) {
      assertThatThrownBy(() -> NuGetPackageUtils.parseNonNegativeParam(value, 20, "skip"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("'skip' is not a valid integer: " + value);
    }

    @Test
    @DisplayName("parseNonNegativeParam rejects a negative value")
    void rejectsNegative() {
      assertThatThrownBy(() -> NuGetPackageUtils.parseNonNegativeParam("-1", 20, "take"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("'take' must not be negative: -1");
    }

    @Test
    @DisplayName("the search page size is clamped to 1000")
    void searchTakeLimit() {
      assertThat(NuGetPackageUtils.MAX_SEARCH_TAKE).isEqualTo(1000);
    }

    @Test
    @DisplayName("copyStreamToFile writes the stream and replaces an existing file")
    void copiesAndReplaces() throws IOException {
      final var target = NuGetPackageUtilsTest.this.tempDir.resolve("copy.nupkg");
      Files.writeString(target, "old content that is longer");

      NuGetPackageUtils.copyStreamToFile(
          new java.io.ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)), target);

      assertThat(Files.readString(target)).isEqualTo("new");
    }

    @Test
    @DisplayName("copyStreamToFile refuses an empty stream")
    void refusesEmptyStream() {
      final var target = NuGetPackageUtilsTest.this.tempDir.resolve("empty.nupkg");

      assertThatThrownBy(
              () ->
                  NuGetPackageUtils.copyStreamToFile(
                      new java.io.ByteArrayInputStream(new byte[0]), target))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("NuGet package stream is empty.");
    }

    private static io.repsy.protocols.shared.repo.dtos.BaseRepoInfo<java.util.UUID> repo(
        final Boolean snapshots, final Boolean releases) {
      return io.repsy.protocols.shared.repo.dtos.BaseRepoInfo.<java.util.UUID>builder()
          .name("nuget")
          .snapshots(snapshots)
          .releases(releases)
          .build();
    }

    @Test
    @DisplayName("checkVersionAllowance refuses a pre-release when snapshots are off")
    void refusesPrereleaseWhenSnapshotsOff() {
      assertThatThrownBy(
              () -> NuGetPackageUtils.checkVersionAllowance("1.0.0-beta", repo(false, true)))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getReason())
                    .isEqualTo("Pre-release packages are not allowed in this repository.");
              });
    }

    @Test
    @DisplayName("checkVersionAllowance refuses a release when releases are off")
    void refusesReleaseWhenReleasesOff() {
      assertThatThrownBy(() -> NuGetPackageUtils.checkVersionAllowance("1.0.0", repo(true, false)))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getReason())
                    .isEqualTo("Release packages are not allowed in this repository.");
              });
    }

    @Test
    @DisplayName("checkVersionAllowance lets a version through unless its kind is switched off")
    void allowsOtherwise() {
      NuGetPackageUtils.checkVersionAllowance("1.0.0-beta", repo(true, false));
      NuGetPackageUtils.checkVersionAllowance("1.0.0", repo(false, true));
      NuGetPackageUtils.checkVersionAllowance("1.0.0-beta", repo(null, null));
      NuGetPackageUtils.checkVersionAllowance("1.0.0", repo(null, null));
    }
  }
}
