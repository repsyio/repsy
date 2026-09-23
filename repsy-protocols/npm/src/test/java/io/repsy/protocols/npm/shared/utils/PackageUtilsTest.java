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

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PackageUtils")
class PackageUtilsTest {

  private static Map<String, Object> versionWithTarball(
      final String name, final String version, final String tarball) {
    final var dist = new HashMap<String, String>();
    dist.put("tarball", tarball);

    final var versionData = new HashMap<String, Object>();
    versionData.put("name", name);
    versionData.put("version", version);
    versionData.put("dist", dist);

    return versionData;
  }

  @SuppressWarnings("unchecked")
  private static String tarballOf(final Map<String, Object> version) {
    return ((Map<String, String>) version.get("dist")).get("tarball");
  }

  @Nested
  @DisplayName("fixTarballUrl (RPS-1205)")
  class FixTarballUrl {

    @Test
    @DisplayName("leaves an already-correct unscoped OS URL unchanged")
    void unscopedUrlUnchanged() throws URISyntaxException {
      final var version =
          versionWithTarball("demo", "1.0.0", "http://h:9090/myrepo/demo/-/demo-1.0.0.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version)).isEqualTo("http://h:9090/myrepo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("leaves an already-correct scoped OS URL unchanged")
    void scopedUrlUnchanged() throws URISyntaxException {
      final var version =
          versionWithTarball(
              "@foo/demo", "1.0.0", "http://h:9090/myrepo/@foo/demo/-/demo-1.0.0.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version)).isEqualTo("http://h:9090/myrepo/@foo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("strips a scope a client duplicated into the tarball filename")
    void stripsDuplicatedScopeFromFilename() throws URISyntaxException {
      final var version =
          versionWithTarball(
              "@foo/demo", "1.0.0", "http://h:9090/myrepo/@foo/demo/-/@foo/demo-1.0.0.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version)).isEqualTo("http://h:9090/myrepo/@foo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("preserves a reverse-proxy path prefix")
    void preservesProxyPrefix() throws URISyntaxException {
      final var version =
          versionWithTarball("demo", "1.0.0", "http://h/ctx/myrepo/demo/-/demo-1.0.0.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version)).isEqualTo("http://h/ctx/myrepo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("leaves a URL with no /-/ untouched and throws nothing")
    void noSeparatorLeftUntouched() throws URISyntaxException {
      final var version = versionWithTarball("demo", "1.0.0", "http://h:9090/myrepo/demo.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version)).isEqualTo("http://h:9090/myrepo/demo.tgz");
    }

    @Test
    @DisplayName("preserves a non-default port and an https scheme")
    void preservesPortAndScheme() throws URISyntaxException {
      final var version =
          versionWithTarball(
              "demo", "1.0.0", "https://registry.example.test:8443/myrepo/demo/-/demo-1.0.0.tgz");

      PackageUtils.fixTarballUrl(version);

      assertThat(tarballOf(version))
          .isEqualTo("https://registry.example.test:8443/myrepo/demo/-/demo-1.0.0.tgz");
    }
  }

  @Nested
  @DisplayName("liftFieldsToTopLevel (RPS-1211)")
  class LiftFieldsToTopLevel {

    private static Map<String, Object> payloadWithVersion(
        final String versionName, final Map<String, Object> version) {
      final var payload = new HashMap<String, Object>();
      payload.put(NpmConstants.VERSIONS, Map.of(versionName, version));

      return payload;
    }

    @Test
    @DisplayName("defaults keywords to an empty List, not a String[] (the regression pin)")
    void keywordsDefaultToEmptyList() {
      final var version = new HashMap<String, Object>();
      final var payload = payloadWithVersion("1.0.0", version);

      PackageUtils.liftFieldsToTopLevel(payload, "1.0.0");

      assertThat(payload.get("keywords")).isInstanceOf(List.class);
      assertThat((List<?>) payload.get("keywords")).isEmpty();
    }

    @Test
    @DisplayName("copies through keywords that are present")
    void keywordsCopiedThrough() {
      final var version = new HashMap<String, Object>();
      version.put("keywords", new ArrayList<>(List.of("a", "b")));
      final var payload = payloadWithVersion("1.0.0", version);

      PackageUtils.liftFieldsToTopLevel(payload, "1.0.0");

      assertThat(payload.get("keywords")).isEqualTo(List.of("a", "b"));
    }

    @Test
    @DisplayName("does not add a maintainers key when absent")
    void maintainersAbsent() {
      final var version = new HashMap<String, Object>();
      final var payload = payloadWithVersion("1.0.0", version);

      PackageUtils.liftFieldsToTopLevel(payload, "1.0.0");

      assertThat(payload).doesNotContainKey("maintainers");
    }

    @Test
    @DisplayName("copies through maintainers that are present")
    void maintainersCopiedThrough() {
      final var version = new HashMap<String, Object>();
      final var maintainers = List.of(Map.of("name", "a"));
      version.put("maintainers", maintainers);
      final var payload = payloadWithVersion("1.0.0", version);

      PackageUtils.liftFieldsToTopLevel(payload, "1.0.0");

      assertThat(payload.get("maintainers")).isEqualTo(maintainers);
    }

    @Test
    @DisplayName("still defaults description, homepage, license, readme and readmeFilename to \"\"")
    void stringFieldsDefaultToEmpty() {
      final var version = new HashMap<String, Object>();
      final var payload = payloadWithVersion("1.0.0", version);

      PackageUtils.liftFieldsToTopLevel(payload, "1.0.0");

      assertThat(payload.get("description")).isEqualTo("");
      assertThat(payload.get("homepage")).isEqualTo("");
      assertThat(payload.get("license")).isEqualTo("");
      assertThat(payload.get("readme")).isEqualTo("");
      assertThat(payload.get("readmeFilename")).isEqualTo("");
    }
  }
}
