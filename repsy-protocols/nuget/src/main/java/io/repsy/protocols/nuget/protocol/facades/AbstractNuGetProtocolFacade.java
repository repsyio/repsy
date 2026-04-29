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
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResource;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetProtocolFacade<ID> implements NuGetProtocolFacade {

  private final NuGetStorageService storageService;
  private final NuGetPackageService<ID> packageService;

  @Override
  public NuGetServiceIndexResponse getServiceIndex(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var repoName = repoInfo.getName();
    final var baseUrl = "http://localhost:9090/" + repoName;

    final var resources = new ArrayList<NuGetServiceIndexResource>();

    resources.add(
        new NuGetServiceIndexResource(baseUrl + "/v3/package", "PackageBaseAddress/3.0.0", null));

    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/registration", "RegistrationsBaseUrl/3.0.0", null));

    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/search", "SearchQueryService/3.0.0-beta", null));

    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/autocomplete", "SearchAutocompleteService/3.0.0-beta", null));

    return new NuGetServiceIndexResponse("3.0.0", resources);
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream pureZipStream)
      throws IOException {
    final byte[] nupkgBytes = pureZipStream.readAllBytes();

    if (nupkgBytes.length == 0) {
      throw new IllegalArgumentException("NuGet package stream is empty.");
    }

    final String nuspecXml = extractNuspec(new ByteArrayInputStream(nupkgBytes));
    final byte[] nuspecBytes = nuspecXml.getBytes(StandardCharsets.UTF_8);

    final var packageId = extractXmlTag(nuspecXml, "id");
    final var version = extractXmlTag(nuspecXml, "version");

    if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Missing 'id' or 'version' in nuspec.");
    }

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    this.storageService.writePackage(
        repoInfo.getStorageKey(), packageId, version, nupkgBytes, nuspecBytes);

    this.packageService.publish(repoInfo, packageId, version, nuspecXml);

    log.info("Successfully published and stored NuGet package {} {}", packageId, version);
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

    this.packageService.incrementDownloadCount(repoInfo, packageId, packageIdVersion.version());

    return resource;
  }

  @Override
  public Resource downloadNuspec(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    final var resource =
        this.storageService.getNuspec(
            repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());

    this.packageService.incrementDownloadCount(repoInfo, packageId, packageIdVersion.version());

    return resource;
  }

  @Override
  public NuGetSearchResponse search(
      final ProtocolContext context,
      final String q,
      final int skip,
      final int take,
      final boolean prerelease) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var results = this.packageService.search(repoInfo, q, skip, take, prerelease);
    return new NuGetSearchResponse((int) results.getTotalElements(), List.of());
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
}
