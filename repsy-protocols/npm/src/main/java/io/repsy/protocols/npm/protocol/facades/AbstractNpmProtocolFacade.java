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
package io.repsy.protocols.npm.protocol.facades;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractNpmProtocolFacade<ID> implements NpmProtocolFacade {

  private static final String USAGES = "usages";
  private static final String PACKAGE_JSON = "package.json";

  private final NpmPackageService<ID> npmPackageService;
  private final AbstractNpmStorageService npmStorageService;

  @Override
  public void publishOrDeprecate(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {
    if (PackageUtils.isMetadataHasDeprecatedVersions(payload)) {
      this.deprecate(context, scopeName, packageName, payload);
    } else {
      this.publish(context, scopeName, packageName, payload);
    }
  }

  @Override
  public String unPublishPackageVersion(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), packageBasePath.resolve(PACKAGE_JSON).toString());

    final var metadata = this.npmStorageService.getMetadata(storagePath, repoInfo.getName());
    final var unpublishedVersion = PackageUtils.findUnpublishedVersion(metadata, payload);

    if (!unpublishedVersion.isEmpty()) {
      this.deletePackageVersion(context, scopeName, packageName, unpublishedVersion);
    }

    return unpublishedVersion;
  }

  @Override
  public Resource getTarball(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final String filename)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    return this.npmStorageService.getTarball(
        repoInfo.getStorageKey(), repoInfo.getName(), scopeName, packageName, filename);
  }

  @Override
  public Map<String, Object> getPackageMetadata(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final String acceptHeader)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var isAbbreviated = PackageUtils.isRequestedAbbreviatedMetadata(acceptHeader);

    return this.npmStorageService.getMetadata(
        repoInfo.getStorageKey(), repoInfo.getName(), scopeName, packageName, isAbbreviated);
  }

  @Override
  public Map<String, String> getMappedDistributionTags(
      final ProtocolContext context, @Nullable final String scopeName, final String packageName) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.getDistributionTags(repoInfo, scopeName, packageName).stream()
        .collect(
            Collectors.toMap(
                PackageDistributionTagMapListItem::getTag,
                PackageDistributionTagMapListItem::getVersion));
  }

  @Override
  public void addDistributionTag(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final String tagName,
      final String versionName)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var metadataAndUsage =
        this.npmStorageService.addDistributionTag(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            tagName,
            versionName.replace("\"", ""));

    final var usage = BaseUsages.ofDisk(metadataAndUsage.getSecond());

    final var packageJsonPath = packageBasePath.resolve(PACKAGE_JSON);
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), packageJsonPath.toString());

    this.npmStorageService.writeMetadataToFile(
        repoInfo.getName(), metadataAndUsage.getFirst(), storagePath);

    this.npmPackageService.addDistributionTag(
        repoInfo.getStorageKey(), scopeName, packageName, tagName, versionName.replace("\"", ""));

    context.addProperty(USAGES, usage);
  }

  @Override
  public void removeDistributionTag(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final String tagName)
      throws IOException {

    if (tagName.equals("latest")) {
      throw new BadRequestException("canNotRemoveTagLatest");
    }

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var path = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var diskUsage =
        this.npmStorageService.removeDistributionTag(
            repoInfo.getStorageKey(), repoInfo.getName(), path, tagName);

    this.npmPackageService.removeDistributionTag(repoInfo, scopeName, packageName, tagName);

    context.addProperty(USAGES, BaseUsages.ofDisk(diskUsage));
  }

  @Override
  public void deletePackage(
      final ProtocolContext context, @Nullable final String scopeName, final String packageName) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var packageInfo =
        this.npmPackageService.getPackage(repoInfo.getStorageKey(), scopeName, packageName);

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var removedBytes =
        this.npmStorageService.deletePackage(repoInfo.getStorageKey(), packageBasePath);

    final var usage = BaseUsages.ofDisk(-1L * removedBytes);

    this.npmPackageService.deletePackage(packageInfo.getId());

    context.addProperty(USAGES, usage);
  }

  private List<PackageDistributionTagMapListItem> getDistributionTags(
      final BaseRepoInfo<ID> baseRepoInfo,
      final @Nullable String scopeName,
      final String packageName) {

    final var packageInfo =
        this.npmPackageService.getPackage(baseRepoInfo.getStorageKey(), scopeName, packageName);

    return this.npmPackageService.getDistributionTags(packageInfo.getId());
  }

  private void deletePackageVersion(
      final ProtocolContext context,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    if (this.npmPackageService.isLastVersion(repoInfo.getStorageKey(), scopeName, packageName)) {
      this.deletePackage(context, scopeName, packageName);
      return;
    }

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var pair =
        this.npmStorageService.deletePackageVersion(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            packageName,
            versionName);

    this.npmPackageService.deletePackageVersion(
        repoInfo, scopeName, packageName, versionName, pair.getFirst());

    final var usage = BaseUsages.ofDisk(pair.getSecond() * -1L);

    context.addProperty(USAGES, usage);
  }

  public void deprecate(
      final ProtocolContext context,
      final @Nullable String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var packageJsonPath = packageBasePath.resolve(PACKAGE_JSON);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), packageJsonPath.toString());
    final var metadata = this.npmStorageService.getMetadata(storagePath, repoInfo.getName());

    PackageUtils.updateModifiedTime(payload);

    final var newMetadataLength = PackageUtils.getMetadataLength(payload);
    final var diskUsageDiff = newMetadataLength - PackageUtils.getMetadataLength(metadata);
    final var usage = BaseUsages.ofDisk(diskUsageDiff);

    this.npmStorageService.writeMetadataToFile(repoInfo.getName(), payload, storagePath);

    this.npmPackageService.handleDeprecations(
        repoInfo.getStorageKey(),
        scopeName,
        packageName,
        PackageUtils.findDeprecatedVersions(metadata, payload));

    context.addProperty(USAGES, usage);
  }

  public void publish(
      final ProtocolContext context,
      final @Nullable String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    try {
      final var packageOptional =
          this.npmPackageService.getPackageInfoByRepoIdAndScopeAndName(
              repoInfo.getStorageKey(), scopeName, packageName);

      final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
      final var versionName = PackageUtils.extractVersionNameFromPayload(payload);

      var usages = BaseUsages.builder().build();

      if (packageOptional.isEmpty()) {
        final var payloadUsage =
            this.npmStorageService.processPackagePayload(payload, repoInfo.getName());

        usages.setDiskUsage(payloadUsage.getFirst() + payloadUsage.getSecond());

        usages =
            this.npmStorageService.writeTarballAndMetadata(
                repoInfo.getStorageKey(),
                repoInfo.getName(),
                payload,
                packageBasePath,
                packageName,
                versionName);

        this.npmPackageService.addPackage(repoInfo, scopeName, packageName, payload);

      } else {
        final var packageVersionOptional =
            this.npmPackageService.findOptPackageVersionByPackageIdAndVersion(
                packageOptional.get().getId(), versionName);

        if (packageVersionOptional.isPresent() && !repoInfo.isAllowOverride()) {
          throw new AccessNotAllowedException("packageVersionAlreadyExists");
        }

        final var pair =
            this.npmStorageService.processVersionPayload(
                payload, packageBasePath, repoInfo.getStorageKey(), repoInfo.getName());

        final var payloadUsage = pair.getFirst();
        usages.setDiskUsage(payloadUsage.getFirst() + payloadUsage.getSecond());

        usages =
            this.npmStorageService.writeTarballAndMetadata(
                repoInfo.getStorageKey(),
                repoInfo.getName(),
                pair.getSecond(),
                packageBasePath,
                packageName,
                versionName);

        if (packageVersionOptional.isEmpty()) {
          this.npmPackageService.addVersionToPackage(packageOptional.get().getId(), payload);
        } else {
          this.npmPackageService.updateVersionFromMetadata(
              repoInfo.getStorageKey(), scopeName, packageName, versionName, payload);
        }
      }

      context.addProperty(USAGES, usages);

    } catch (final ClassCastException | URISyntaxException _) {
      throw new BadRequestException("badRequest");
    }
  }
}
