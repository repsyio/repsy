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
package io.repsy.protocols.nuget.shared.mappers;

import io.repsy.protocols.nuget.shared.dtos.NuGetCatalogEntry;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchData;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchVersionData;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@UtilityClass
public final class NuGetResponseMapper {

  public static NuGetSearchData toSearchData(
      final NuGetPackageSearchResult result, final String baseUrl) {

    final var idLower = result.packageId().toLowerCase(Locale.ROOT);
    final var latestLower = result.latestVersion().toLowerCase(Locale.ROOT);
    final var registrationBase = NuGetUrlBuilder.registrationBase(baseUrl, idLower);
    final var leafId = NuGetUrlBuilder.leafUrl(registrationBase, latestLower);

    final var versionDatas =
        result.versions().stream()
            .map(
                v -> {
                  final var vLower = v.version().toLowerCase(Locale.ROOT);
                  return new NuGetSearchVersionData(
                      NuGetUrlBuilder.leafUrl(registrationBase, vLower),
                      v.version(),
                      v.downloads());
                })
            .toList();

    return new NuGetSearchData(
        leafId,
        "Package",
        registrationBase + "/index.json",
        result.packageId(),
        result.latestVersion(),
        result.description(),
        null,
        result.title(),
        result.iconUrl(),
        result.licenseUrl(),
        result.projectUrl(),
        splitTags(result.tags()),
        splitAuthors(result.authors()),
        result.totalDownloads(),
        false,
        versionDatas);
  }

  public static NuGetCatalogEntry toCatalogEntry(
      final NuGetVersionInfo v, final String packageId, final String leafUrl) {
    return new NuGetCatalogEntry(
        leafUrl,
        "PackageDetails",
        packageId,
        v.version(),
        v.description(),
        v.authors(),
        v.title(),
        v.iconUrl(),
        v.licenseUrl(),
        v.projectUrl(),
        v.tags(),
        v.listed(),
        v.publishedAt());
  }

  public static NuGetRegistrationLeafItem toLeafItem(
      final NuGetVersionInfo v,
      final String registrationBase,
      final String packageBase,
      final String packageId) {

    final var vLower = v.version().toLowerCase(Locale.ROOT);
    final var idLower = packageId.toLowerCase(Locale.ROOT);
    final var leafUrl = NuGetUrlBuilder.leafUrl(registrationBase, vLower);
    final var packageContent = NuGetUrlBuilder.nupkgUrl(packageBase, idLower, vLower);

    return new NuGetRegistrationLeafItem(
        leafUrl,
        "Package",
        toCatalogEntry(v, packageId, leafUrl),
        v.listed(),
        packageContent,
        v.publishedAt(),
        registrationBase + "/index.json");
  }

  public static NuGetRegistrationLeafResponse toLeafResponse(
      final NuGetVersionInfo v,
      final String registrationBase,
      final String packageBase,
      final String packageId) {

    final var vLower = v.version().toLowerCase(Locale.ROOT);
    final var idLower = packageId.toLowerCase(Locale.ROOT);
    final var leafUrl = NuGetUrlBuilder.leafUrl(registrationBase, vLower);
    final var packageContent = NuGetUrlBuilder.nupkgUrl(packageBase, idLower, vLower);

    return new NuGetRegistrationLeafResponse(
        leafUrl,
        "Package",
        toCatalogEntry(v, packageId, leafUrl),
        v.listed(),
        packageContent,
        v.publishedAt(),
        registrationBase + "/index.json");
  }

  private static @Nullable List<String> splitTags(final @Nullable String tags) {
    return tags != null && !tags.isBlank() ? Arrays.asList(tags.split("\\s+")) : null;
  }

  private static @Nullable List<String> splitAuthors(final @Nullable String authors) {
    return authors != null && !authors.isBlank() ? Arrays.asList(authors.split(",\\s*")) : null;
  }
}
