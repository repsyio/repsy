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

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResource;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetProtocolFacade<ID> implements NuGetProtocolFacade {

  private static final int THREE = 3;
  private static final int FOUR = 4;
  private final NuGetStorageService storageService;
  private final NuGetPackageService<ID> packageService;

  @Override
  public NuGetServiceIndexResponse getServiceIndex(final ProtocolContext context) {
    final var baseUrl = this.buildBaseUrl(context);
    final var resources = new ArrayList<NuGetServiceIndexResource>();

    resources.add(
        new NuGetServiceIndexResource(baseUrl + "/v3/package", "PackageBaseAddress/3.0.0", null));
    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/registration", "RegistrationBaseUrl/3.0.0", null));
    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/search", "SearchQueryService/3.0.0-beta", null));
    resources.add(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/autocomplete", "SearchAutocompleteService/3.0.0-beta", null));

    return new NuGetServiceIndexResponse("3.0.0", resources);
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
      throws IOException {
    try {
      final var nuspecXml = NuGetPackageUtils.extractNuspecFromNupkg(inputStream);
      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

      final var packageId = this.extractXmlTag(nuspecXml, "id");
      final var version = this.extractXmlTag(nuspecXml, "version");

      if (packageId == null || packageId.isEmpty() || version == null || version.isEmpty()) {
        throw new IOException("Missing id or version in nuspec");
      }

      this.packageService.publish(repoInfo, packageId, version, nuspecXml);
      log.info("Published NuGet package {} {}", packageId, version);
    } catch (final IOException e) {
      log.error("Failed to publish nupkg", e);
      throw e;
    }
  }

  private @Nullable String extractXmlTag(final String xml, final String tagName) {
    try {
      final var patternStr = String.format("<%s>([^<]+)</%s>", tagName, tagName);
      final var matcher = Pattern.compile(patternStr).matcher(xml);
      if (matcher.find()) {
        return matcher.group(1).trim();
      }
    } catch (final Exception e) {
      log.debug("Failed to extract {} from nuspec", tagName, e);
    }
    return null;
  }

  @Override
  public List<String> getPackageVersions(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = this.extractPackageId(context);
    return this.packageService.getVersions(repoInfo, packageId);
  }

  @Override
  public Resource downloadNupkg(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = this.extractPackageIdAndVersion(context);
    return this.storageService.getNupkg(
        repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());
  }

  @Override
  public Resource downloadNuspec(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = this.extractPackageIdAndVersion(context);
    return this.storageService.getNuspec(
        repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());
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

  private String extractPackageId(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return parts.length > THREE ? parts[THREE] : "";
  }

  private PackageIdVersion extractPackageIdAndVersion(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return new PackageIdVersion(
        parts.length > THREE ? parts[THREE] : "", parts.length > FOUR ? parts[FOUR] : "");
  }

  private String buildBaseUrl(final ProtocolContext context) {
    // TODO: Extract actual base URL from HTTP request in handler
    // For now return default; should be implemented at handler level with HttpServletRequest
    return "http://localhost:8080";
  }

  private record PackageIdVersion(String id, String version) {}
}
