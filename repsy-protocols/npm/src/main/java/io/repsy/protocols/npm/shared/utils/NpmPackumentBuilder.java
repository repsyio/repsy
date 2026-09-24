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

import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot.Maintainer;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot.Version;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Rebuilds a package's metadata (its packument, the {@code package.json} of the storage layout)
 * from what the database holds, for a package whose file is gone from storage or corrupt. The
 * database is the source of truth, so the rebuilt metadata lists exactly the versions and dist-tags
 * of its rows.
 *
 * <p>Per version, the metadata is the manifest from the version's tarball where there is one (the
 * only place the dependencies, scripts and the like survive), completed by the columns of the row
 * for everything the manifest lacks. The name, version and deprecation always come from the row:
 * the tarball cannot know that a version was deprecated. Nothing is made up: a field neither source
 * has is left out, and {@code dist} carries only what could be derived (the tarball URL from the
 * registry address, the digests from the tarball).
 *
 * <p>What no source keeps, and so a rebuild cannot bring back, is everything the publishing client
 * added to the version outside its {@code package.json}: {@code readme}, {@code readmeFilename},
 * {@code gitHead}, {@code _nodeVersion}, {@code _npmVersion}, {@code dist.fileCount}, {@code
 * dist.unpackedSize} and {@code dist.signatures}. A version whose tarball is gone as well is
 * rebuilt from its row alone: no dependencies and no digests.
 */
@UtilityClass
@NullMarked
public final class NpmPackumentBuilder {

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /**
   * Builds the metadata.
   *
   * @param tarballs what each version's tarball vouches for, by version name; a version without an
   *     entry has no readable tarball
   * @param packageUrl the address of the package in the registry (without a trailing slash), from
   *     which each {@code dist.tarball} follows; {@code null} or blank leaves {@code dist.tarball}
   *     out
   * @param modified the time to record as the last modification
   */
  public static Map<String, Object> build(
      final NpmPackageSnapshot snapshot,
      final Map<String, NpmTarballFacts> tarballs,
      final @Nullable String packageUrl,
      final Instant modified) {

    final var fullName = PackageUtils.buildFullName(snapshot.scope(), snapshot.name());
    final var versions = new LinkedHashMap<String, Object>();
    final var time = new LinkedHashMap<String, Object>();

    time.put("created", TIME_FORMAT.format(snapshot.createdAt()));
    time.put(NpmConstants.MODIFIED, TIME_FORMAT.format(modified));

    for (final var version : snapshot.versions()) {
      versions.put(
          version.version(),
          buildVersion(
              fullName, snapshot.name(), version, tarballs.get(version.version()), packageUrl));
      time.put(version.version(), TIME_FORMAT.format(version.createdAt()));
    }

    final var packument = new LinkedHashMap<String, Object>();

    packument.put(NpmConstants.ID, fullName);
    packument.put(NpmConstants.NAME, fullName);
    packument.put(NpmConstants.DIST_TAGS, distTagsOf(snapshot));
    packument.put("time", time);
    packument.put(NpmConstants.VERSIONS, versions);

    liftLatest(packument, snapshot);

    return packument;
  }

  /** The tags of the rows; {@code latest} is put back from the package row if the tags lack it. */
  private static Map<String, String> distTagsOf(final NpmPackageSnapshot snapshot) {

    final var tags = new LinkedHashMap<>(snapshot.distTags());

    if (!tags.containsKey(NpmConstants.LATEST) && snapshot.latest() != null) {
      tags.put(NpmConstants.LATEST, snapshot.latest());
    }

    return tags;
  }

  /** Like a publish of the latest version does: its top-level fields are lifted to the package. */
  private static void liftLatest(
      final Map<String, Object> packument, final NpmPackageSnapshot snapshot) {

    final var latest = distTagsOf(snapshot).get(NpmConstants.LATEST);

    if (latest != null && ((Map<?, ?>) packument.get(NpmConstants.VERSIONS)).containsKey(latest)) {
      PackageUtils.liftFieldsToTopLevel(packument, latest);
    }
  }

  private static Map<String, Object> buildVersion(
      final String fullName,
      final String bareName,
      final Version row,
      final @Nullable NpmTarballFacts tarball,
      final @Nullable String packageUrl) {

    final var manifest = new LinkedHashMap<String, Object>();

    if (tarball != null) {
      manifest.putAll(tarball.manifest());
    }

    // Registry fields, which no package.json legitimately carries: the rows and the tarball itself
    // are what says whether a version is deprecated and what its dist is.
    manifest.remove(NpmConstants.DEPRECATED);
    manifest.remove(NpmConstants.DIST);

    manifest.put(NpmConstants.NAME, fullName);
    manifest.put("version", row.version());
    manifest.put(NpmConstants.ID, fullName + "@" + row.version());

    fillFromRow(manifest, row);

    if (row.deprecation() != null) {
      manifest.put(NpmConstants.DEPRECATED, row.deprecation());
    }

    final var dist = distOf(bareName, row.version(), tarball, packageUrl);

    if (!dist.isEmpty()) {
      manifest.put(NpmConstants.DIST, dist);
    }

    return manifest;
  }

  /** Puts what the columns say, unless the manifest already says it. */
  private static void fillFromRow(final Map<String, Object> manifest, final Version row) {

    putIfAbsent(manifest, "description", row.description());
    putIfAbsent(manifest, "homepage", row.homepage());
    putIfAbsent(manifest, "license", row.license());
    putIfAbsent(
        manifest,
        NpmConstants.REPOSITORY,
        mapOf("type", row.repositoryType(), "url", row.repositoryUrl()));
    putIfAbsent(
        manifest,
        NpmConstants.AUTHOR,
        mapOf(
            NpmConstants.NAME,
            row.authorName(),
            NpmConstants.EMAIL,
            row.authorEmail(),
            NpmConstants.URL,
            row.authorUrl()));
    putIfAbsent(
        manifest,
        NpmConstants.BUGS,
        mapOf(NpmConstants.URL, row.bugsUrl(), NpmConstants.EMAIL, row.bugsEmail()));

    if (!row.keywords().isEmpty()) {
      manifest.putIfAbsent("keywords", List.copyOf(row.keywords()));
    }

    if (!row.maintainers().isEmpty()) {
      manifest.putIfAbsent(NpmConstants.MAINTAINERS, maintainersOf(row.maintainers()));
    }
  }

  private static void putIfAbsent(
      final Map<String, Object> manifest, final String key, final @Nullable Object value) {

    if (value != null) {
      manifest.putIfAbsent(key, value);
    }
  }

  private static List<Map<String, String>> maintainersOf(final List<Maintainer> maintainers) {

    final var all = new ArrayList<Map<String, String>>();

    for (final var maintainer : maintainers) {
      final var entry =
          mapOf(
              NpmConstants.NAME,
              maintainer.name(),
              NpmConstants.EMAIL,
              maintainer.email(),
              NpmConstants.URL,
              maintainer.url());

      if (entry != null) {
        all.add(entry);
      }
    }

    return all;
  }

  /** The entries whose value is set, or {@code null} when none is. Takes key, value pairs. */
  private static @Nullable Map<String, String> mapOf(final @Nullable String... keysAndValues) {

    final var map = new LinkedHashMap<String, String>();

    for (var i = 0; i < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        map.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }

    return map.isEmpty() ? null : map;
  }

  private static Map<String, Object> distOf(
      final String bareName,
      final String version,
      final @Nullable NpmTarballFacts tarball,
      final @Nullable String packageUrl) {

    final var dist = new LinkedHashMap<String, Object>();

    if (packageUrl != null && !packageUrl.isBlank()) {
      dist.put(
          NpmConstants.TARBALL,
          packageUrl + "/-/" + PackageUtils.getTarballFilename(bareName, version));
    }

    if (tarball != null) {
      dist.put("shasum", tarball.shasum());
      dist.put("integrity", tarball.integrity());
    }

    return dist;
  }
}
