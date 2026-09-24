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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService.PublishKind;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService.MetadataChange;
import io.repsy.protocols.npm.shared.utils.NpmPublishLimits;
import io.repsy.protocols.npm.shared.utils.NpmRevPath;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@RequiredArgsConstructor
@NullMarked
public abstract class AbstractNpmProtocolFacade<ID> implements NpmProtocolFacade {

  private static final String USAGES = "usages";
  private static final String PACKAGE_JSON = "package.json";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  private final NpmPackageService<ID> npmPackageService;
  private final AbstractNpmStorageService npmStorageService;

  @Override
  public void publishOrDeprecate(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    PackageUtils.checkPackageNameMatchesUrl(payload, scopeName, packageName);

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

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var metadata =
        this.npmStorageService.readMetadataOrRebuild(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            this.snapshotOf(repoInfo, scopeName, packageName));
    final var unpublishedVersion = PackageUtils.findUnpublishedVersion(metadata, payload);

    this.deletePackageVersion(context, scopeName, packageName, unpublishedVersion);

    return unpublishedVersion;
  }

  @Override
  public void deletePackageTarball(
      final ProtocolContext context,
      @Nullable final String scopeName,
      final String packageName,
      final String tarballFilename) {

    final var versionName =
        NpmRevPath.versionOfTarball(packageName, tarballFilename)
            .orElseThrow(() -> new BadRequestException("badRequest"));
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    // The tarball is removed with the version by the packument PUT that comes first in an unpublish
    // (RPS-1289), so this request finds nothing left to delete. A version that is still published
    // is not deleted here: removing its file alone would leave a version without a tarball.
    if (this.isPublished(repoInfo, scopeName, packageName, versionName)) {
      throw new ItemAlreadyExistException("npmVersionStillPublished");
    }
  }

  private boolean isPublished(
      final BaseRepoInfo<ID> repoInfo,
      @Nullable final String scopeName,
      final String packageName,
      final String versionName) {

    try {
      final var packageInfo =
          this.npmPackageService.getPackage(repoInfo.getStorageKey(), scopeName, packageName);

      return this.npmPackageService.getVersionNames(packageInfo.getId()).contains(versionName);
    } catch (final ItemNotFoundException _) {
      return false;
    }
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

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var isAbbreviated = PackageUtils.isRequestedAbbreviatedMetadata(acceptHeader);

    // A package the database has and storage lost is served from the rows (RPS-1300): it is what a
    // client reads before it unpublishes, deprecates or tags the package.
    return this.npmStorageService.getMetadata(
        repoInfo.getStorageKey(),
        repoInfo.getName(),
        scopeName,
        packageName,
        isAbbreviated,
        this.snapshotOf(repoInfo, scopeName, packageName));
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

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var version = versionName.replace("\"", "");
    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), packageBasePath.resolve(PACKAGE_JSON).toString());

    // The tag row is written first and the package metadata second, in one transaction that holds
    // the package row locked (RPS-1272): see NpmPackageService#addDistributionTag.
    final var usages =
        this.npmPackageService.addDistributionTag(
            repoInfo,
            scopeName,
            packageName,
            tagName,
            version,
            () ->
                this.rewriteMetadata(
                    repoInfo,
                    scopeName,
                    packageName,
                    packageBasePath,
                    () -> {
                      final var metadataAndUsage =
                          this.npmStorageService.addDistributionTag(
                              repoInfo.getStorageKey(),
                              repoInfo.getName(),
                              packageBasePath,
                              tagName,
                              version);

                      this.npmStorageService.writeMetadataToFile(
                          repoInfo.getName(), metadataAndUsage.getFirst(), storagePath);

                      return metadataAndUsage.getSecond();
                    }));

    context.addProperty(USAGES, usages);
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
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var usages =
        this.npmPackageService.removeDistributionTag(
            repoInfo,
            scopeName,
            packageName,
            tagName,
            () ->
                this.rewriteMetadata(
                    repoInfo,
                    scopeName,
                    packageName,
                    packageBasePath,
                    () ->
                        this.npmStorageService.removeDistributionTag(
                            repoInfo.getStorageKey(),
                            repoInfo.getName(),
                            packageBasePath,
                            tagName)));

    context.addProperty(USAGES, usages);
  }

  /**
   * Runs {@code change} while {@link NpmPackageService} still holds the package row locked, and
   * puts the metadata back as it was when the change fails, so the metadata never keeps a tag the
   * rolled-back rows do not have. A package whose metadata file is gone is rebuilt from its rows
   * first (RPS-1300): see {@link AbstractNpmStorageService#changeMetadata}.
   */
  private BaseUsages rewriteMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final Path packageBasePath,
      final MetadataChange change)
      throws IOException {

    return BaseUsages.ofDisk(
        this.npmStorageService.changeMetadata(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            this.snapshotOf(repoInfo, scopeName, packageName),
            change));
  }

  /** The rows of the package as the transaction that is open sees them, when asked for. */
  private Supplier<NpmPackageSnapshot> snapshotOf(
      final BaseRepoInfo<ID> repoInfo, final @Nullable String scopeName, final String packageName) {

    return () ->
        this.npmPackageService.getSnapshot(repoInfo.getStorageKey(), scopeName, packageName);
  }

  @Override
  public void deletePackage(
      final ProtocolContext context, @Nullable final String scopeName, final String packageName) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    // The rows are deleted first and the files second, in one transaction that holds the package
    // row locked (RPS-1280): see NpmPackageService#deletePackage.
    final var deletion =
        this.npmPackageService.deletePackage(
            repoInfo,
            scopeName,
            packageName,
            () -> this.removePackageFiles(repoInfo, packageBasePath));

    context.addProperty(USAGES, deletion.usages());
  }

  private BaseUsages removePackageFiles(
      final BaseRepoInfo<ID> repoInfo, final Path packageBasePath) {

    return BaseUsages.ofDisk(
        -1L * this.npmStorageService.deletePackage(repoInfo.getStorageKey(), packageBasePath));
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
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    // The rows are deleted first and the files second, in one transaction that holds the package
    // row locked (RPS-1280): see NpmPackageService#deletePackageVersion. Deleting the last version
    // deletes the package.
    final var deletion =
        this.npmPackageService.deletePackageVersion(
            repoInfo,
            scopeName,
            packageName,
            versionName,
            newLatest ->
                BaseUsages.ofDisk(
                    this.npmStorageService.removeVersion(
                        repoInfo.getStorageKey(),
                        repoInfo.getName(),
                        packageBasePath,
                        packageName,
                        versionName,
                        newLatest,
                        this.snapshotOf(repoInfo, scopeName, packageName))),
            () -> this.removePackageFiles(repoInfo, packageBasePath));

    context.addProperty(USAGES, deletion.usages());
  }

  public void deprecate(
      final ProtocolContext context,
      final @Nullable String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var metadata =
        this.npmStorageService.readMetadataOrRebuild(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            this.snapshotOf(repoInfo, scopeName, packageName));
    final var deprecations = PackageUtils.findDeprecatedVersions(metadata, payload);

    // The deprecation rows are written first and the package metadata second, in one transaction
    // that holds the package row locked (RPS-1280): see NpmPackageService#handleDeprecations.
    final var usages =
        this.npmPackageService.handleDeprecations(
            repoInfo,
            scopeName,
            packageName,
            deprecations,
            () ->
                this.rewriteMetadata(
                    repoInfo,
                    scopeName,
                    packageName,
                    packageBasePath,
                    () ->
                        this.npmStorageService.deprecateVersions(
                            repoInfo.getStorageKey(),
                            repoInfo.getName(),
                            packageBasePath,
                            deprecations)));

    context.addProperty(USAGES, usages);
  }

  public void publish(
      final ProtocolContext context,
      final @Nullable String scopeName,
      final String packageName,
      final Map<String, Object> payload)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    try {
      final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
      final var versionName = PackageUtils.extractVersionNameFromPayload(payload);

      // Guard every length-limited value before anything is written (RPS-1136): a publish this
      // refuses leaves no orphan tarball, and one it lets through never fails the row insert.
      NpmPublishLimits.checkScopeAndName(scopeName, packageName);
      NpmPublishLimits.checkVersion(versionName);
      NpmPublishLimits.checkDistTags(payload);
      NpmPublishLimits.dropOverLongFields(
          PackageUtils.extractVersionFromPayload(payload).getSecond());

      // The rows are written first and the files second, in one transaction (RPS-1124): see
      // NpmPackageService#publishVersion.
      final var usages =
          this.npmPackageService.publishVersion(
              repoInfo,
              scopeName,
              packageName,
              versionName,
              payload,
              kind ->
                  this.storeVersion(
                      repoInfo,
                      scopeName,
                      packageBasePath,
                      packageName,
                      versionName,
                      payload,
                      kind));

      context.addProperty(ARTIFACT_NAME, this.buildArtifactName(scopeName, packageName));
      context.addProperty(ARTIFACT_VERSION, versionName);
      context.addProperty(
          STORAGE_PATH,
          packageBasePath
              .resolve(PackageUtils.getTarballFilename(packageName, versionName))
              .toString());
      context.addProperty(USAGES, usages);

    } catch (final ClassCastException | URISyntaxException _) {
      throw new BadRequestException("badRequest");
    }
  }

  /**
   * Stores the tarball and the package metadata while {@link NpmPackageService#publishVersion}
   * still holds the version's rows: a failure rolls the rows back, and the files of a version the
   * publish was adding are removed, so storage does not keep a version the database does not know.
   */
  private BaseUsages storeVersion(
      final BaseRepoInfo<ID> repoInfo,
      final @Nullable String scopeName,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final Map<String, Object> payload,
      final PublishKind kind)
      throws IOException, URISyntaxException {

    if (kind != PublishKind.REPLACES_VERSION) {
      this.noteOrphanedTarball(repoInfo, packageBasePath, packageName, versionName);
    }

    // Read while the package row is locked, so no other publish can change it before this one has
    // written its own.
    final var previousMetadata =
        kind == PublishKind.NEW_VERSION
            ? this.npmStorageService.readMetadataBytes(
                repoInfo.getStorageKey(), repoInfo.getName(), packageBasePath)
            : null;

    try {
      return this.writeFiles(
          repoInfo, scopeName, packageBasePath, packageName, versionName, payload, kind);
    } catch (final IOException | URISyntaxException | RuntimeException e) {
      // The rows are rolled back with this failure. A version being replaced keeps its row, so
      // its files are left alone.
      if (kind != PublishKind.REPLACES_VERSION) {
        this.discardPartialVersion(
            repoInfo, packageBasePath, packageName, versionName, previousMetadata, e);
      }
      throw e;
    }
  }

  /**
   * Notes a tarball that is in storage although the database has no row for its version.
   *
   * <p>The rows are written before the files, under the package row lock, so a tarball without a
   * row can only be what an earlier publish left behind when it failed (all publishes before
   * RPS-1124 wrote the files first). The database is the authority on which versions exist, so the
   * orphan is not a version that {@code allowOverride} protects: this publish replaces it, and if
   * it fails the tarball is discarded with the rest of the partial version.
   */
  private void noteOrphanedTarball(
      final BaseRepoInfo<ID> repoInfo,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    if (this.npmStorageService.tarballExists(
        repoInfo.getStorageKey(), repoInfo.getName(), packageBasePath, packageName, versionName)) {
      log.warn(
          "Replacing an orphaned tarball of npm package {} {} in repo {}: storage has it, the"
              + " database has no such version",
          packageName,
          versionName,
          repoInfo.getName());
    }
  }

  private BaseUsages writeFiles(
      final BaseRepoInfo<ID> repoInfo,
      final @Nullable String scopeName,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final Map<String, Object> payload,
      final PublishKind kind)
      throws IOException, URISyntaxException {

    final var metadata =
        kind == PublishKind.NEW_PACKAGE
            ? this.processNewPackage(repoInfo, payload)
            : this.npmStorageService
                .processVersionPayload(
                    payload,
                    packageBasePath,
                    repoInfo.getStorageKey(),
                    repoInfo.getName(),
                    this.snapshotOf(repoInfo, scopeName, packageName))
                .getSecond();

    return this.npmStorageService.writeTarballAndMetadata(
        repoInfo.getStorageKey(),
        repoInfo.getName(),
        metadata,
        packageBasePath,
        packageName,
        versionName);
  }

  private Map<String, Object> processNewPackage(
      final BaseRepoInfo<ID> repoInfo, final Map<String, Object> payload)
      throws URISyntaxException {

    this.npmStorageService.processPackagePayload(payload, repoInfo.getName());

    return payload;
  }

  private void discardPartialVersion(
      final BaseRepoInfo<ID> repoInfo,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final byte @Nullable [] previousMetadata,
      final Exception cause) {

    try {
      this.npmStorageService.discardPublishedVersion(
          repoInfo.getStorageKey(),
          repoInfo.getName(),
          packageBasePath,
          packageName,
          versionName,
          previousMetadata);
    } catch (final IOException | RuntimeException e) {
      // The publish's own failure is the one to report; the leftover is noted on it.
      log.warn(
          "Could not remove the partly written files of npm package {} {}",
          packageName,
          versionName,
          e);
      cause.addSuppressed(e);
    }
  }

  private String buildArtifactName(final @Nullable String scopeName, final String packageName) {
    return PackageUtils.buildFullName(scopeName, packageName);
  }
}
