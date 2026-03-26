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
package io.repsy.protocols.npm.shared.npm_package.services;

import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageVersionInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.util.Pair;

public interface NpmPackageService<ID> {

  void addPackage(
      BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull Map<String, Object> payload);

  BasePackageInfo<ID> getPackage(
      UUID storageKey, @Nullable String scopeName, @NonNull String packageName);

  @NonNull Optional<BasePackageInfo<ID>> getPackageInfoByRepoIdAndScopeAndName(
      @NonNull UUID repoUuid, @Nullable String scopeName, @NonNull String packageName);

  void deletePackage(ID id);

  void addVersionToPackage(@NonNull ID packageId, @NonNull Map<String, Object> payload);

  @NonNull Optional<BasePackageVersionInfo<ID>> findOptPackageVersionByPackageIdAndVersion(
      @NonNull ID packageId, @NonNull String versionName);

  void updateVersionFromMetadata(
      UUID storageKey,
      @Nullable String scopeName,
      @NonNull String packageName,
      String versionName,
      @NonNull Map<String, Object> payload);

  void handleDeprecations(
      UUID storageKey,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull List<Pair<String, String>> deprecatedVersions);

  boolean isLastVersion(UUID storageKey, @Nullable String scopeName, @NonNull String packageName);

  void deletePackageVersion(
      BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String versionName,
      String first);

  @NonNull List<PackageDistributionTagMapListItem> getDistributionTags(ID id);

  void addDistributionTag(
      UUID storageKey,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String tagName,
      String replace);

  void removeDistributionTag(
      BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String tagName);
}
