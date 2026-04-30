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
package io.repsy.os.server.protocols.nuget.shared.packages.mappers;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@NullMarked
public interface NuGetPackageConverter {

  @Mapping(source = "packageId", target = "packageId")
  @Mapping(source = "v.version", target = "version")
  @Mapping(source = "v.title", target = "title")
  @Mapping(source = "v.description", target = "description")
  @Mapping(source = "v.authors", target = "authors")
  @Mapping(source = "v.tags", target = "tags")
  @Mapping(source = "v.iconUrl", target = "iconUrl")
  @Mapping(source = "v.licenseUrl", target = "licenseUrl")
  @Mapping(source = "v.projectUrl", target = "projectUrl")
  @Mapping(source = "v.listed", target = "listed")
  @Mapping(source = "v.downloadCount", target = "downloadCount")
  @Mapping(source = "v.publishedAt", target = "publishedAt")
  NuGetVersionInfo toVersionInfo(NuGetPackageVersion v, String packageId);

  @Mapping(source = "version", target = "version")
  @Mapping(source = "downloadCount", target = "downloads")
  NuGetPackageSearchResult.VersionSummary toVersionSummary(NuGetPackageVersion v);

  default NuGetPackageSearchResult toSearchResult(
      final NuGetPackage pkg,
      final boolean prerelease,
      final List<NuGetPackageVersion> allVersions) {

    final var filteredVersions =
        allVersions.stream()
            .filter(v -> !v.isPrerelease() || prerelease)
            .sorted(Comparator.comparing(NuGetPackageVersion::getPublishedAt).reversed())
            .toList();

    final var latestVersion =
        filteredVersions.isEmpty()
            ? allVersions.stream().findFirst()
            : filteredVersions.stream().findFirst();

    final var versionSummaries = filteredVersions.stream().map(this::toVersionSummary).toList();

    final long totalDownloads =
        filteredVersions.stream().mapToLong(NuGetPackageVersion::getDownloadCount).sum();

    return latestVersion
        .map(
            latest ->
                new NuGetPackageSearchResult(
                    pkg.getPackageId(),
                    latest.getVersion(),
                    latest.getTitle(),
                    latest.getDescription(),
                    latest.getAuthors(),
                    latest.getTags(),
                    latest.getIconUrl(),
                    latest.getLicenseUrl(),
                    latest.getProjectUrl(),
                    totalDownloads,
                    versionSummaries))
        .orElseGet(
            () ->
                new NuGetPackageSearchResult(
                    pkg.getPackageId(),
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    List.of()));
  }
}
