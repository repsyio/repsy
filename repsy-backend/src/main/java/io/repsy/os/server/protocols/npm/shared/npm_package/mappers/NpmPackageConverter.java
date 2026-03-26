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
package io.repsy.os.server.protocols.npm.shared.npm_package.mappers;

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageDistributionTagListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageInfo;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionDetail;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionInfo;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.shared.repo.entities.Repo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@NullMarked
public interface NpmPackageConverter {

  default Map<String, Object> setVersionMetaData(final PackageVersion packageVersion) {
    final Map<String, Object> versionMetadata = new HashMap<>();
    versionMetadata.put("author_name", packageVersion.getAuthorName());
    versionMetadata.put("author_email", packageVersion.getAuthorEmail());
    versionMetadata.put("author_url", packageVersion.getAuthorUrl());
    versionMetadata.put("bugs_url", packageVersion.getBugsUrl());
    versionMetadata.put("bugs_email", packageVersion.getBugsEmail());
    versionMetadata.put("repository_type", packageVersion.getRepositoryType());
    versionMetadata.put("repository_url", packageVersion.getRepositoryUrl());
    versionMetadata.put("homepage", packageVersion.getHomepage());
    versionMetadata.put("license", packageVersion.getLicense());
    versionMetadata.put("deprecated", packageVersion.isDeprecated());
    return versionMetadata;
  }

  default PackageVersionDetail toPackageVersionDetail(
      final PackageInfo packageInfo,
      final PackageVersionInfo packageVersionInfo,
      final List<PackageKeywordListItem> keywords,
      final List<PackageMaintainerListItem> maintainers,
      final List<PackageDistributionTagListItem> distributionTags,
      final String readmeFileContent) {

    return PackageVersionDetail.builder()
        .id(packageVersionInfo.getId())
        .scopeName(packageInfo.getScopeName())
        .packageName(packageInfo.getPackageName())
        .versionName(packageVersionInfo.getVersion())
        .authorEmail(packageVersionInfo.getAuthorEmail())
        .authorName(packageVersionInfo.getAuthorName())
        .authorUrl(packageVersionInfo.getAuthorUrl())
        .bugsEmail(packageVersionInfo.getBugsEmail())
        .bugsUrl(packageVersionInfo.getBugsUrl())
        .description(packageVersionInfo.getDescription())
        .homepage(packageVersionInfo.getHomepage())
        .license(packageVersionInfo.getLicense())
        .repositoryType(packageVersionInfo.getRepositoryType())
        .repositoryUrl(packageVersionInfo.getRepositoryUrl())
        .deprecated(packageVersionInfo.isDeprecated())
        .deprecationMessage(packageVersionInfo.getDeprecationMessage())
        .deleted(packageVersionInfo.isDeleted())
        .createdAt(packageVersionInfo.getCreatedAt())
        .keywords(keywords)
        .maintainers(maintainers)
        .distributionTags(distributionTags)
        .readme(readmeFileContent)
        .build();
  }

  default PackageVersionInfo toPackageVersionInfo(
      final PackageVersion packageVersion, final NpmPackage npmPackage) {
    return PackageVersionInfo.builder()
        .id(packageVersion.getId())
        .packageScope(npmPackage.getScope())
        .packageName(npmPackage.getName())
        .version(packageVersion.getVersion())
        .authorEmail(packageVersion.getAuthorEmail())
        .authorName(packageVersion.getAuthorName())
        .authorUrl(packageVersion.getAuthorUrl())
        .bugsEmail(packageVersion.getBugsEmail())
        .bugsUrl(packageVersion.getBugsUrl())
        .description(packageVersion.getDescription())
        .homepage(packageVersion.getHomepage())
        .license(packageVersion.getLicense())
        .repositoryType(packageVersion.getRepositoryType())
        .repositoryUrl(packageVersion.getRepositoryUrl())
        .deprecated(packageVersion.isDeprecated())
        .deprecationMessage(packageVersion.getDeprecationMessage())
        .createdAt(packageVersion.getCreatedAt())
        .build();
  }

  default PackageInfo toPackageInfo(final NpmPackage npmPackage, final Repo repo) {
    return PackageInfo.builder()
        .id(npmPackage.getId())
        .registryName(repo.getName())
        .scopeName(npmPackage.getScope())
        .packageName(npmPackage.getName())
        .latest(npmPackage.getLatest())
        .createdAt(npmPackage.getCreatedAt())
        .build();
  }
}
