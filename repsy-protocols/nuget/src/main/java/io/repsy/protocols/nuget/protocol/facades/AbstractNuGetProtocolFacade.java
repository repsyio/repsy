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

import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractNuspec;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractPackageId;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractPackageIdAndVersion;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.extractXmlTag;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationIndexResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationPageItem;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import io.repsy.protocols.nuget.shared.mappers.NuGetResponseMapper;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.nuget.shared.utils.NuGetServiceIndexResources;
import io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.semver4j.Semver;
import org.springframework.core.io.Resource;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetProtocolFacade<ID> implements NuGetProtocolFacade {

  public static final String USAGES = "usages";

  /** Maximum number of leaf items per registration page per the NuGet spec. */
  private static final int REGISTRATION_PAGE_SIZE = 64;

  // NuGet versions may be 4-part (1.0.0.0) — coerce handles them; fallback to string compare
  private static final List<String> REGISTRATION_INDEX_TYPES =
      List.of("catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink");

  private static final Comparator<String> VERSION_COMPARATOR =
      (v1, v2) -> {
        try {
          return Objects.requireNonNull(Semver.coerce(v1))
              .compareTo(Objects.requireNonNull(Semver.coerce(v2)));
        } catch (final Exception e) {
          return v1.compareToIgnoreCase(v2);
        }
      };

  private final NuGetStorageService storageService;
  private final NuGetPackageService<ID> packageService;

  protected void doPublish(
      final BaseRepoInfo<ID> repoInfo,
      final String packageId,
      final String version,
      final String nuspecXml)
      throws IOException {

    this.packageService.publish(repoInfo, packageId, version, nuspecXml);
  }

  @Override
  public NuGetServiceIndexResponse getServiceIndex(
      final ProtocolContext context, final String baseUrl) {

    return new NuGetServiceIndexResponse(
        "https://schema.nuget.org/schema#", "3.0.0", NuGetServiceIndexResources.build(baseUrl));
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream pureZipStream)
      throws IOException {

    final Path tempFile = Files.createTempFile("nuget-", ".nupkg");
    try {
      final long size = Files.copy(pureZipStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

      if (size == 0) {
        throw new IllegalArgumentException("NuGet package stream is empty.");
      }

      final String nuspecXml;
      try (final var is = Files.newInputStream(tempFile)) {
        nuspecXml = extractNuspec(is);
      }
      final byte[] nuspecBytes = nuspecXml.getBytes(StandardCharsets.UTF_8);

      final var packageId = extractXmlTag(nuspecXml, "id");
      final var version = extractXmlTag(nuspecXml, "version");

      if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) {
        throw new IllegalArgumentException("Missing 'id' or 'version' in nuspec.");
      }

      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

      final BaseUsages usages;
      try (final var nupkgStream = Files.newInputStream(tempFile)) {
        usages =
            this.storageService.writePackage(
                repoInfo.getStorageKey(), packageId, version, nupkgStream, nuspecBytes);
      }

      this.doPublish(repoInfo, packageId, version, nuspecXml);

      log.info("Successfully published and stored NuGet package {} {}", packageId, version);

      context.addProperty(USAGES, usages);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Override
  public List<String> getPackageVersions(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);

    return this.packageService.getVersions(repoInfo, packageId);
  }

  @Override
  public Resource downloadNuPackage(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    final var resource =
        this.storageService.getNupkg(
            repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());

    try {
      this.packageService.incrementDownloadCount(repoInfo, packageId, packageIdVersion.version());
    } catch (final Exception e) {
      log.warn(
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
      final int skip,
      final int take,
      final boolean prerelease) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
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
          "https://schema.nuget.org/schema#", indexUrl, REGISTRATION_INDEX_TYPES, 0, List.of());
    }

    final var packageBase = NuGetUrlBuilder.packageBase(baseUrl, idLower);

    final var leafItems =
        versionInfos.stream()
            .map(v -> NuGetResponseMapper.toLeafItem(v, registrationBase, packageBase, packageId))
            .toList();

    final var pages = buildRegistrationPages(leafItems, indexUrl);

    return new NuGetRegistrationIndexResponse(
        "https://schema.nuget.org/schema#",
        indexUrl,
        REGISTRATION_INDEX_TYPES,
        pages.size(),
        pages);
  }

  @Override
  public NuGetRegistrationLeafResponse getRegistrationLeaf(
      final ProtocolContext context, final String baseUrl) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    final var packageId = packageIdVersion.id();
    final var rawVersion = packageIdVersion.version();
    // path segment is "{version}.json" for leaf URLs — strip the extension
    final var version =
        rawVersion.endsWith(".json")
            ? rawVersion.substring(0, rawVersion.length() - ".json".length())
            : rawVersion;

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

  /**
   * Partitions leaf items into pages of at most {@value #REGISTRATION_PAGE_SIZE} per the NuGet
   * registration spec. If all items fit in one page they are inlined; otherwise each page gets its
   * own {@code @id} URL derived from the index URL.
   */
  private static List<NuGetRegistrationPageItem> buildRegistrationPages(
      final List<NuGetRegistrationLeafItem> leafItems, final String indexUrl) {

    if (leafItems.size() <= REGISTRATION_PAGE_SIZE) {
      final var lowerVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .min(VERSION_COMPARATOR)
              .orElse("");
      final var upperVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .max(VERSION_COMPARATOR)
              .orElse("");
      return List.of(
          new NuGetRegistrationPageItem(
              indexUrl,
              "catalog:CatalogPage",
              leafItems.size(),
              leafItems,
              lowerVersion,
              upperVersion));
    }

    // Split into pages of REGISTRATION_PAGE_SIZE
    final var pages = new ArrayList<NuGetRegistrationPageItem>();
    int pageIndex = 0;
    for (int offset = 0; offset < leafItems.size(); offset += REGISTRATION_PAGE_SIZE) {
      final var chunk =
          leafItems.subList(offset, Math.min(offset + REGISTRATION_PAGE_SIZE, leafItems.size()));
      final var pageUrl = indexUrl.replace("/index.json", "/page/" + pageIndex + ".json");
      final var lowerVersion =
          chunk.stream().map(i -> i.catalogEntry().version()).min(VERSION_COMPARATOR).orElse("");
      final var upperVersion =
          chunk.stream().map(i -> i.catalogEntry().version()).max(VERSION_COMPARATOR).orElse("");
      pages.add(
          new NuGetRegistrationPageItem(
              pageUrl, "catalog:CatalogPage", chunk.size(), chunk, lowerVersion, upperVersion));
      pageIndex++;
    }
    return pages;
  }
}
