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
package io.repsy.protocols.nuget.protocol.facades;

import static io.repsy.protocols.nuget.shared.mappers.NuGetResponseMapper.toLeafItem;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.FORMAT_JSON;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.NUGET_CONTEXT;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.buildRegistrationPages;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.checkVersionAllowance;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.copyStreamToFile;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractPackageId;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractPackageIdAndVersion;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.normalizeNuGetVersion;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.readNuspecMetadata;
import static io.repsy.protocols.nuget.shared.utils.NuGetServiceIndexResources.build;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.protocol.facades.dtos.NuspecMetadata;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationIndexResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import io.repsy.protocols.nuget.shared.mappers.NuGetResponseMapper;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetProtocolFacade<ID> implements NuGetProtocolFacade {

  private final NuGetStorageService storageService;
  private final NuGetPackageService<ID> packageService;

  public static final String USAGES = "usages";
  public static final String SUPPORTED_VERSION = "3.0.0";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";
  private static final String NUPKG_STORAGE_PATH_FMT = "packages/%s/%s/%s.%s.nupkg";
  private static final List<String> REGISTRATION_INDEX_TYPES =
      List.of("catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink");

  protected void doPublish(
      final BaseRepoInfo<ID> repoInfo, final ID pkgId, final String version, final String nuspecXml)
      throws IOException {

    this.packageService.publishVersion(repoInfo, pkgId, version, nuspecXml);
  }

  @Override
  public NuGetServiceIndexResponse getServiceIndex(
      final ProtocolContext context, final String baseUrl) {

    return new NuGetServiceIndexResponse(NUGET_CONTEXT, SUPPORTED_VERSION, build(baseUrl));
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream pureZipStream)
      throws IOException {

    final Path tempFile = Files.createTempFile("nuget-", ".nupkg");

    try {
      copyStreamToFile(pureZipStream, tempFile);

      final var metadata = readNuspecMetadata(tempFile);
      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

      checkVersionAllowance(metadata.version(), repoInfo);

      // Fail fast before writing to storage — avoids orphan disk writes when override is denied
      if (!repoInfo.isAllowOverride()
          && this.packageService.versionExists(
              repoInfo, metadata.packageId(), metadata.version())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Version "
                + metadata.version()
                + " of package "
                + metadata.packageId()
                + " already exists.");
      }

      final var pkgId = this.packageService.findOrCreatePackage(repoInfo, metadata.packageId());
      final var usages = this.storePackage(repoInfo, metadata, tempFile);
      this.doPublish(repoInfo, pkgId, metadata.version(), metadata.nuspecXml());

      log.info(
          "Successfully published and stored NuGet package {} {}",
          metadata.packageId(),
          metadata.version());

      final var normalizedId = metadata.packageId().toLowerCase(Locale.ROOT);
      final var normalizedVersion = normalizeNuGetVersion(metadata.version());

      context.addProperty(ARTIFACT_NAME, metadata.packageId());
      context.addProperty(ARTIFACT_VERSION, metadata.version());
      context.addProperty(
          STORAGE_PATH,
          String.format(
              NUPKG_STORAGE_PATH_FMT,
              normalizedId,
              normalizedVersion,
              normalizedId,
              normalizedVersion));
      context.addProperty(USAGES, usages);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Override
  public List<String> getPackageVersions(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);

    // Flat container spec: return ALL versions including unlisted
    return this.packageService.getAllVersionInfos(repoInfo, packageId).stream()
        .map(v -> v.version().toLowerCase(Locale.ROOT))
        .toList();
  }

  @Override
  public Resource downloadNuPackage(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    final var resource =
        this.storageService.getNuPkg(
            repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());

    try {
      this.packageService.incrementDownloadCount(repoInfo, packageId, packageIdVersion.version());
    } catch (final Exception e) {
      log.debug(
          "Failed to increment download count for {} {}: {}",
          packageId,
          packageIdVersion.version(),
          e.getMessage());
    }

    return resource;
  }

  @Override
  public void unlistVersion(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);

    this.packageService.unlistVersion(repoInfo, packageIdVersion.id(), packageIdVersion.version());
  }

  @Override
  public void relistVersion(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);

    this.packageService.relistVersion(repoInfo, packageIdVersion.id(), packageIdVersion.version());
  }

  @Override
  public Resource downloadNuspec(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);

    return this.storageService.getNuspec(
        repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());
  }

  @Override
  public NuGetSearchResponse search(
      final ProtocolContext context,
      final String q,
      final int skip,
      final int take,
      final boolean prerelease,
      final String baseUrl) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var page = this.packageService.search(repoInfo, q, skip, take, prerelease);

    final var data =
        page.getContent().stream()
            .map(result -> NuGetResponseMapper.toSearchData(result, baseUrl))
            .toList();

    return new NuGetSearchResponse((int) page.getTotalElements(), data);
  }

  @Override
  public NuGetAutocompleteResponse autocomplete(
      final ProtocolContext context,
      final String q,
      final @Nullable String id,
      final int skip,
      final int take,
      final boolean prerelease) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    if (id != null && !id.isBlank()) {
      List<String> versions = this.packageService.getVersions(repoInfo, id);
      if (!prerelease) {
        versions = versions.stream().filter(v -> !v.contains("-")).toList();
      }
      return new NuGetAutocompleteResponse(versions.size(), versions);
    }

    final var results = this.packageService.autocomplete(repoInfo, q, skip, take, prerelease);
    return new NuGetAutocompleteResponse(results.size(), results);
  }

  @Override
  public NuGetRegistrationIndexResponse getRegistrationIndex(
      final ProtocolContext context, final String baseUrl) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);
    final var idLower = packageId.toLowerCase(Locale.ROOT);
    final var registrationBase = NuGetUrlBuilder.registrationBase(baseUrl, idLower);
    final var indexUrl = NuGetUrlBuilder.leafUrl(registrationBase, "index");

    final var versionInfos = this.packageService.getAllVersionInfos(repoInfo, packageId);

    if (versionInfos.isEmpty()) {
      return new NuGetRegistrationIndexResponse(
          NUGET_CONTEXT, indexUrl, REGISTRATION_INDEX_TYPES, 0, List.of());
    }

    final var packageBase = NuGetUrlBuilder.packageBase(baseUrl, idLower);

    final var leafItems =
        versionInfos.stream()
            .map(v -> toLeafItem(v, registrationBase, packageBase, packageId))
            .toList();

    final var pages = buildRegistrationPages(leafItems, indexUrl);

    return new NuGetRegistrationIndexResponse(
        NUGET_CONTEXT, indexUrl, REGISTRATION_INDEX_TYPES, pages.size(), pages);
  }

  @Override
  public NuGetRegistrationLeafResponse getRegistrationLeaf(
      final ProtocolContext context, final String baseUrl) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    final var packageId = packageIdVersion.id();
    final var rawVersion = packageIdVersion.version();
    final var version = this.normalizeVersion(rawVersion);

    final var versionInfo =
        this.packageService
            .findVersionInfo(repoInfo, packageId, version)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Version " + version + " not found for package " + packageId));

    final var idLower = packageId.toLowerCase(Locale.ROOT);
    final var registrationBase = NuGetUrlBuilder.registrationBase(baseUrl, idLower);
    final var packageBase = NuGetUrlBuilder.packageBase(baseUrl, idLower);

    return NuGetResponseMapper.toLeafResponse(
        versionInfo, registrationBase, packageBase, packageId);
  }

  private String normalizeVersion(final String rawVersion) {
    // path segment is "{version}.json" for leaf URLs — strip the extension

    return rawVersion.endsWith(FORMAT_JSON)
        ? rawVersion.substring(0, rawVersion.length() - FORMAT_JSON.length())
        : rawVersion;
  }

  private BaseUsages storePackage(
      final BaseRepoInfo<ID> repoInfo, final NuspecMetadata metadata, final Path tempFile)
      throws IOException {

    final var nuspecBytes = metadata.nuspecXml().getBytes(StandardCharsets.UTF_8);

    try (final var nuPkgStream = Files.newInputStream(tempFile)) {

      return this.storageService.writePackage(
          repoInfo.getStorageKey(),
          metadata.packageId(),
          metadata.version(),
          nuPkgStream,
          nuspecBytes);
    }
  }
}
