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

import io.repsy.protocols.npm.shared.constants.NpmConstants;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("unchecked")
@UtilityClass
/** The {@code dist.tarball} address of a version: normalizing, building and rewriting it. */
public final class NpmTarballUrlUtils {
  /**
   * Normalizes a published version's {@code dist.tarball} without assuming any particular registry
   * URL layout.
   *
   * <p>The client (npm itself) already computes a servable path, so this only rebuilds the filename
   * after the last {@code /-/} segment from the version's own {@code name} and {@code version} and
   * leaves everything before it untouched. That is a no-op for an already-correct URL, and it still
   * strips a scope a client duplicated into the filename (e.g. {@code /-/@foo/demo-0.2.1.tgz}
   * becomes {@code /-/demo-0.2.1.tgz}). A URL with no {@code /-/} is left alone.
   */
  public static void fixTarballUrl(final Map<String, Object> version) throws URISyntaxException {

    final var dist = Objects.requireNonNull((Map<String, String>) version.get("dist"));
    final var uri = new URI(Objects.requireNonNull(dist.get("tarball")));
    final var rawPath = uri.getRawPath();

    final var idx = rawPath.lastIndexOf("/-/");

    if (idx < 0) {
      return; // nothing recognisable to fix; leave the client's URL alone
    }

    final var packageName = (String) version.get("name");
    final var versionName = (String) version.get("version");
    final var fileName =
        NpmPackageUtils.getTarballFilename(
            bareName(Objects.requireNonNull(packageName)), String.valueOf(versionName));

    dist.put(
        "tarball",
        uri.getScheme()
            + "://"
            + uri.getAuthority()
            + rawPath.substring(0, idx)
            + "/-/"
            + fileName);
  }

  /**
   * The address of a version's tarball in the registry: {@code <base>/<repoName>/<fullName>/-/<bare
   * name>-<version>.tgz}, the layout every client and {@code
   * AbstractNpmPackageDownloadProtocolMethodHandler} agree on. A trailing slash on {@code base} is
   * dropped, and the base may carry a path prefix.
   */
  public static String buildTarballUrl(
      final String base, final String repoName, final String fullName, final String versionName) {

    return base.replaceAll("/+$", "")
        + "/"
        + repoName
        + "/"
        + fullName
        + "/-/"
        + NpmPackageUtils.getTarballFilename(bareName(fullName), versionName);
  }

  /**
   * Points every version's {@code dist.tarball} at the registry address {@code base}, whatever the
   * publisher sent (its host, its port, the {@code http://} that libnpmpublish and yarn classic
   * write for an HTTPS registry). Clients fetch the URL as served and withhold their credentials
   * from another origin, so the registry has to name itself, as every other npm registry does. A
   * version with no {@code dist.tarball} is left as it is, and a version without a {@code name} of
   * its own takes the package's.
   */
  public static void rewriteTarballUrls(
      final Map<String, Object> metadata, final String base, final String repoName) {

    if (!(metadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions)) {
      return;
    }

    final var packageName = metadata.get(NpmConstants.NAME);

    for (final var entry : versions.entrySet()) {
      if (entry.getKey() instanceof final String versionName
          && entry.getValue() instanceof final Map<?, ?> version) {
        rewriteTarballUrl(version, packageName, versionName, base, repoName);
      }
    }
  }

  private static void rewriteTarballUrl(
      final Map<?, ?> version,
      final @Nullable Object packageName,
      final String versionName,
      final String base,
      final String repoName) {

    final var fullName =
        version.get(NpmConstants.NAME) instanceof final String own ? own : packageName;

    if (version.get("dist") instanceof final Map<?, ?> dist
        && dist.containsKey(NpmConstants.TARBALL)
        && fullName instanceof final String name) {
      ((Map<String, Object>) dist)
          .put(NpmConstants.TARBALL, buildTarballUrl(base, repoName, name, versionName));
    }
  }

  private static String bareName(final String packageName) {

    final var slash = packageName.indexOf('/');

    return slash < 0 ? packageName : packageName.substring(slash + 1);
  }
}
