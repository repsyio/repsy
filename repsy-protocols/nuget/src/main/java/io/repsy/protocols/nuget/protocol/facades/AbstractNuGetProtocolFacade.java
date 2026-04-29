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
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetProtocolFacade<ID> implements NuGetProtocolFacade {

  private final NuGetStorageService storageService;
  private final NuGetPackageService<ID> packageService;

  @Override
  public NuGetServiceIndexResponse getServiceIndex(final ProtocolContext context) {
    final var baseUrl = buildBaseUrl(context);
    final var resources = List.<io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResource>of();
    return new NuGetServiceIndexResponse("3.0.0", resources);
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
      throws IOException {
    // TODO: Implement publish (parse .nupkg ZIP, extract .nuspec, parse XML metadata)
  }

  @Override
  public List<String> getPackageVersions(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageId = extractPackageId(context);
    return this.packageService.getVersions(repoInfo, packageId);
  }

  @Override
  public Resource downloadNupkg(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var packageIdVersion = extractPackageIdAndVersion(context);
    return this.storageService.getNupkg(
        repoInfo.getStorageKey(), packageIdVersion.id(), packageIdVersion.version());
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
    // Extract from path like /v3/package/{id}/index.json
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return parts.length > 3 ? parts[3] : "";
  }

  private PackageIdVersion extractPackageIdAndVersion(final ProtocolContext context) {
    // Extract from path like /v3/package/{id}/{version}/{filename}.nupkg
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return new PackageIdVersion(
        parts.length > 3 ? parts[3] : "",
        parts.length > 4 ? parts[4] : "");
  }

  private String buildBaseUrl(final ProtocolContext context) {
    // TODO: Extract base URL from request context for service index resources
    return "";
  }

  private record PackageIdVersion(String id, String version) {}
}
