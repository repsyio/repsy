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
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.util.Pair;

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
  @DisplayName("buildTarballUrl and rewriteTarballUrls (RPS-1333)")
  class TarballUrls {

    private static Map<String, Object> packument(final Map<String, Object> versions) {
      final var packument = new HashMap<String, Object>();
      packument.put(NpmConstants.NAME, "demo");
      packument.put(NpmConstants.VERSIONS, versions);

      return packument;
    }

    @SuppressWarnings("unchecked")
    private static String tarballOfVersion(
        final Map<String, Object> packument, final String versionName) {
      final var versions = (Map<String, Object>) packument.get(NpmConstants.VERSIONS);

      return tarballOf((Map<String, Object>) versions.get(versionName));
    }

    @Test
    @DisplayName("builds the URL of an unscoped package")
    void buildsUnscopedUrl() {
      assertThat(PackageUtils.buildTarballUrl("http://h:9090", "myrepo", "demo", "1.0.0"))
          .isEqualTo("http://h:9090/myrepo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("builds the URL of a scoped package with the scope in the path only")
    void buildsScopedUrl() {
      assertThat(PackageUtils.buildTarballUrl("https://h", "myrepo", "@foo/demo", "1.0.0"))
          .isEqualTo("https://h/myrepo/@foo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("keeps a path prefix of the base and drops its trailing slashes")
    void keepsPrefixAndDropsTrailingSlash() {
      assertThat(PackageUtils.buildTarballUrl("https://h/ctx//", "myrepo", "demo", "1.0.0"))
          .isEqualTo("https://h/ctx/myrepo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("points every version at the base, whichever host and scheme it had")
    void rewritesEveryVersion() {
      final var packument =
          packument(
              Map.of(
                  "1.0.0",
                      versionWithTarball("demo", "1.0.0", "http://a:9090/r/demo/-/demo-1.0.0.tgz"),
                  "2.0.0",
                      versionWithTarball(
                          "demo", "2.0.0", "http://b/x/y/demo/-/@s/demo-2.0.0.tgz")));

      PackageUtils.rewriteTarballUrls(packument, "https://repo.test", "myrepo");

      assertThat(tarballOfVersion(packument, "1.0.0"))
          .isEqualTo("https://repo.test/myrepo/demo/-/demo-1.0.0.tgz");
      assertThat(tarballOfVersion(packument, "2.0.0"))
          .isEqualTo("https://repo.test/myrepo/demo/-/demo-2.0.0.tgz");
    }

    @Test
    @DisplayName("a version without a name of its own takes the package's")
    void versionTakesThePackageName() {
      final var version = versionWithTarball("demo", "1.0.0", "http://a/r/demo/-/demo-1.0.0.tgz");
      version.remove("name");
      final var packument = packument(Map.of("1.0.0", version));

      PackageUtils.rewriteTarballUrls(packument, "https://repo.test", "myrepo");

      assertThat(tarballOfVersion(packument, "1.0.0"))
          .isEqualTo("https://repo.test/myrepo/demo/-/demo-1.0.0.tgz");
    }

    @Test
    @DisplayName("leaves a version without a dist, or a dist without a tarball, as it is")
    void leavesVersionsWithoutTarball() {
      final var noDist = new HashMap<String, Object>(Map.of("name", "demo", "version", "1.0.0"));
      final var noTarball =
          new HashMap<String, Object>(
              Map.of("name", "demo", "version", "2.0.0", "dist", new HashMap<String, Object>()));
      final var packument = packument(Map.of("1.0.0", noDist, "2.0.0", noTarball));

      PackageUtils.rewriteTarballUrls(packument, "https://repo.test", "myrepo");

      assertThat(noDist).doesNotContainKey("dist");
      assertThat(noTarball.get("dist")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("does nothing to a packument without versions, or with malformed ones")
    void toleratesMalformedPackuments() {
      final var noVersions = new HashMap<String, Object>(Map.of("name", "demo"));
      final var malformed = packument(Map.of("1.0.0", "not a map"));

      PackageUtils.rewriteTarballUrls(noVersions, "https://repo.test", "myrepo");
      PackageUtils.rewriteTarballUrls(malformed, "https://repo.test", "myrepo");

      assertThat(noVersions).containsOnlyKeys("name");
      assertThat(malformed.get(NpmConstants.VERSIONS)).isEqualTo(Map.of("1.0.0", "not a map"));
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

  @Nested
  @DisplayName("resolveLatestVersion (RPS-1208)")
  class ResolveLatestVersion {

    private Map<String, Object> metadataWithVersions(final String... versionNames) {
      final Map<String, Object> versions = new HashMap<>();
      for (final var name : versionNames) {
        versions.put(name, Map.of());
      }
      return new HashMap<>(Map.of("versions", versions));
    }

    @Test
    @DisplayName("returns an empty string when there are no versions")
    void emptyWhenNoVersions() {
      assertThat(PackageUtils.resolveLatestVersion(this.metadataWithVersions())).isEmpty();
    }

    @Test
    @DisplayName("picks the numerically greatest version, not the lexically greatest")
    void picksNumericallyGreatest() {
      final var metadata = this.metadataWithVersions("1.9.0", "1.10.0", "1.2.0");
      assertThat(PackageUtils.resolveLatestVersion(metadata)).isEqualTo("1.10.0");
    }

    @Test
    @DisplayName(
        "resolves a version with a part above int32 max as latest among smaller versions"
            + " (the RPS-1208 regression)")
    void resolvesAboveInt32MaxAsLatest() {
      final var metadata = this.metadataWithVersions("1.0.0", "2.0.0", "2147483648.0.0");
      assertThat(PackageUtils.resolveLatestVersion(metadata)).isEqualTo("2147483648.0.0");
    }

    @Test
    @DisplayName("does not pick a version above int32 max when a larger one exists")
    void doesNotPickAboveInt32MaxWhenNotLatest() {
      final var metadata = this.metadataWithVersions("2147483648.0.0", "2147483649.0.0");
      assertThat(PackageUtils.resolveLatestVersion(metadata)).isEqualTo("2147483649.0.0");
    }
  }

  @Nested
  @DisplayName("buildFullName")
  class BuildFullName {

    @Test
    @DisplayName("returns the bare name when there is no scope")
    void noScope() {
      assertThat(PackageUtils.buildFullName(null, "demo")).isEqualTo("demo");
    }

    @Test
    @DisplayName("returns @scope/name when there is a scope")
    void withScope() {
      assertThat(PackageUtils.buildFullName("foo", "demo")).isEqualTo("@foo/demo");
    }
  }

  @Nested
  @DisplayName("checkPackageNameMatchesUrl (RPS-1207)")
  class CheckPackageNameMatchesUrl {

    private Map<String, Object> payload(
        final String name, final String id, final Map<String, Object> versions) {
      final var payload = new HashMap<String, Object>();
      payload.put("name", name);
      payload.put("_id", id);
      payload.put("versions", versions);
      return payload;
    }

    @Test
    @DisplayName("accepts a body whose name, _id and versions[*].name all match the URL")
    void acceptsMatchingBody() {
      final var version = new HashMap<String, Object>(Map.of("name", "demo", "version", "1.0.0"));
      final var payload = this.payload("demo", "demo", Map.of("1.0.0", version));

      assertThatCode(() -> PackageUtils.checkPackageNameMatchesUrl(payload, null, "demo"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts a scoped body whose name matches @scope/name")
    void acceptsMatchingScopedBody() {
      final var version =
          new HashMap<String, Object>(Map.of("name", "@foo/demo", "version", "1.0.0"));
      final var payload = this.payload("@foo/demo", "@foo/demo", Map.of("1.0.0", version));

      assertThatCode(() -> PackageUtils.checkPackageNameMatchesUrl(payload, "foo", "demo"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a body whose top-level name does not match the URL")
    void refusesMismatchedName() {
      final var payload = this.payload("b", "a", Map.of());

      assertThatThrownBy(() -> PackageUtils.checkPackageNameMatchesUrl(payload, null, "a"))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageNameMismatch");
    }

    @Test
    @DisplayName("refuses a body whose _id does not match the URL")
    void refusesMismatchedId() {
      final var payload = this.payload("a", "b", Map.of());

      assertThatThrownBy(() -> PackageUtils.checkPackageNameMatchesUrl(payload, null, "a"))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageNameMismatch");
    }

    @Test
    @DisplayName("refuses a body whose versions[*].name does not match the URL")
    void refusesMismatchedVersionName() {
      final var version = new HashMap<String, Object>(Map.of("name", "b", "version", "1.0.0"));
      final var payload = this.payload("a", "a", Map.of("1.0.0", version));

      assertThatThrownBy(() -> PackageUtils.checkPackageNameMatchesUrl(payload, null, "a"))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageNameMismatch");
    }

    @Test
    @DisplayName("does not refuse a body missing name/_id/versions entirely")
    void ignoresAbsentFields() {
      final var payload = new HashMap<String, Object>();

      assertThatCode(() -> PackageUtils.checkPackageNameMatchesUrl(payload, null, "a"))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("resolveLatestVersion and findDeprecatedVersions (RPS-1280)")
  class RemovalHelpers {

    @Test
    @DisplayName("resolves the highest version by semver, not by name")
    void highestBySemver() {
      assertThat(PackageUtils.resolveLatestVersion(List.of("1.9.0", "1.10.0", "1.2.0")))
          .isEqualTo("1.10.0");
    }

    @Test
    @DisplayName("an empty list has no latest version")
    void noVersionsNoLatest() {
      assertThat(PackageUtils.resolveLatestVersion(List.<String>of())).isEmpty();
    }

    @Test
    @DisplayName("a version the payload lacks is not reported as deprecated")
    void aVersionPublishedAfterTheClientReadIsSkipped() {
      final var stored =
          metadataOf(
              Map.of(
                  "1.0.0", new HashMap<String, Object>(), "1.1.0", new HashMap<String, Object>()));
      final var deprecatedOne = new HashMap<String, Object>();
      deprecatedOne.put("deprecated", "use 1.1");
      final var sent = metadataOf(Map.of("1.0.0", deprecatedOne));

      assertThat(PackageUtils.findDeprecatedVersions(stored, sent))
          .containsExactly(Pair.of("1.0.0", "use 1.1"));
    }

    private Map<String, Object> metadataOf(final Map<String, Object> versions) {
      final var metadata = new HashMap<String, Object>();
      metadata.put("versions", new HashMap<>(versions));

      return metadata;
    }
  }

  @Nested
  @DisplayName("findUnpublishedVersion (RPS-1289)")
  class FindUnpublishedVersion {

    private Map<String, Object> packument(final String... versions) {
      final var all = new HashMap<String, Object>();

      for (final var version : versions) {
        all.put(version, new HashMap<String, Object>());
      }

      final var metadata = new HashMap<String, Object>();
      metadata.put("versions", all);

      return metadata;
    }

    @Test
    @DisplayName("is the one stored version the payload lacks")
    void theOneMissingVersion() {
      assertThat(
              PackageUtils.findUnpublishedVersion(
                  this.packument("1.0.0", "1.1.0", "2.0.0"), this.packument("1.0.0", "2.0.0")))
          .isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("ignores a version only the payload has")
    void aVersionOnlyThePayloadHas() {
      assertThat(
              PackageUtils.findUnpublishedVersion(
                  this.packument("1.0.0", "1.1.0"), this.packument("1.0.0", "0.9.0")))
          .isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("is the only version when the payload has none left")
    void theLastVersion() {
      assertThat(PackageUtils.findUnpublishedVersion(this.packument("1.0.0"), this.packument()))
          .isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("is a conflict when the payload lacks no version")
    void nothingMissing() {
      assertThatThrownBy(
              () ->
                  PackageUtils.findUnpublishedVersion(
                      this.packument("1.0.0"), this.packument("1.0.0")))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("unpublishPayloadStale");
    }

    @Test
    @DisplayName("is a conflict when a version was published after the client read the package")
    void stalePayload() {
      // The client removed 1.0.0 from what it read; 2.0.0 was published in between.
      assertThatThrownBy(
              () ->
                  PackageUtils.findUnpublishedVersion(
                      this.packument("1.0.0", "2.0.0"), this.packument()))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("unpublishPayloadStale");
    }

    @Test
    @DisplayName("is a bad request when the payload has no versions object")
    void payloadWithoutVersions() {
      assertThatThrownBy(
              () -> PackageUtils.findUnpublishedVersion(this.packument("1.0.0"), new HashMap<>()))
          .isInstanceOf(BadRequestException.class);
    }
  }
}
