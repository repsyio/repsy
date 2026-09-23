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
package io.repsy.protocols.nuget.shared.utils;

import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResource;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

@NullMarked
@UtilityClass
public final class NuGetServiceIndexResources {

  /**
   * RPS-1240: NuGet.Client's {@code ServiceTypes.SearchQueryService} is {@code {"/Versioned",
   * "/3.4.0", "/3.0.0-beta"}} and {@code SearchAutocompleteService} is {@code {"/Versioned",
   * "/3.0.0-beta"}}: the bare, unversioned spellings the service-index docs also list are NOT in
   * the client's vocabulary (only {@code RegistrationsBaseUrl} has a bare form there), so a client
   * that only saw the bare type reported "The source does not have a Search service!". The bare
   * type stays advertised too (for clients that follow the docs to the letter), next to the
   * versioned type the NuGet client resolves; both point to the same URL, which the client queries
   * once. {@code /3.0.0-beta} rather than {@code /3.4.0} because this server does not honour {@code
   * semVerLevel}.
   */
  public static List<NuGetServiceIndexResource> build(final String baseUrl) {

    final var searchUrl = baseUrl + "/v3/search";
    final var autocompleteUrl = baseUrl + "/v3/autocomplete";

    return List.of(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/package", "PackageBaseAddress/3.0.0", "Package download base URL"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/package", "PackagePublish/2.0.0", "Package publish endpoint"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/registration", "RegistrationsBaseUrl", "Package registration base URL"),
        new NuGetServiceIndexResource(searchUrl, "SearchQueryService", "Package search service"),
        new NuGetServiceIndexResource(
            searchUrl, "SearchQueryService/3.0.0-beta", "Package search service"),
        new NuGetServiceIndexResource(
            autocompleteUrl, "SearchAutocompleteService", "Package autocomplete service"),
        new NuGetServiceIndexResource(
            autocompleteUrl,
            "SearchAutocompleteService/3.0.0-beta",
            "Package autocomplete service"));
  }
}
