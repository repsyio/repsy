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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1213 / RPS-1240: {@code NuGetServiceIndexResources.build} used to advertise {@code @type}
 * strings NuGet.Client does not recognise. The client matches {@code @type} by exact string against
 * a fixed vocabulary, so an unrecognised type is silently treated as "resource absent" -- which
 * made {@code dotnet package search} fail with "The source does not have a Search service!" even
 * though the underlying routes worked. RPS-1213 switched to the bare spellings the service-index
 * docs list, which fixed only {@code RegistrationsBaseUrl}: RPS-1240 found (live, .NET SDK
 * 10.0.401) that NuGet.Client's {@code SearchQueryService} and {@code SearchAutocompleteService}
 * vocabularies contain no bare form, only the versioned ones.
 */
class NuGetServiceIndexResourcesTest {

  /**
   * NuGet.Client's recognised {@code @type} vocabulary for these three resource kinds, copied from
   * {@code ServiceTypes.cs}
   * (https://github.com/NuGet/NuGet.Client/blob/dev/src/NuGet.Core/NuGet.Protocol/ServiceTypes.cs).
   * A type not in these sets is invisible to the client, even though the docs describe the bare
   * search spellings.
   */
  private static final Set<String> KNOWN_REGISTRATIONS_BASE_URL_TYPES =
      Set.of(
          "RegistrationsBaseUrl",
          "RegistrationsBaseUrl/Versioned",
          "RegistrationsBaseUrl/3.0.0-beta",
          "RegistrationsBaseUrl/3.0.0-rc",
          "RegistrationsBaseUrl/3.4.0",
          "RegistrationsBaseUrl/3.6.0");

  private static final Set<String> KNOWN_SEARCH_QUERY_SERVICE_TYPES =
      Set.of(
          "SearchQueryService/Versioned",
          "SearchQueryService/3.4.0",
          "SearchQueryService/3.0.0-beta");

  private static final Set<String> KNOWN_SEARCH_AUTOCOMPLETE_SERVICE_TYPES =
      Set.of("SearchAutocompleteService/Versioned", "SearchAutocompleteService/3.0.0-beta");

  private static final String BASE_URL = "https://repsy.example/some-repo";

  @Test
  @DisplayName("serves exactly the expected resources, dropping the invented PackageDelete type")
  void servesExactlyTheExpectedResources() {
    final var resources = NuGetServiceIndexResources.build(BASE_URL);

    assertThat(resources)
        .containsExactly(
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/package", "PackageBaseAddress/3.0.0", "Package download base URL"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/package", "PackagePublish/2.0.0", "Package publish endpoint"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/registration",
                "RegistrationsBaseUrl",
                "Package registration base URL"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/search", "SearchQueryService", "Package search service"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/search", "SearchQueryService/3.0.0-beta", "Package search service"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/autocomplete",
                "SearchAutocompleteService",
                "Package autocomplete service"),
            new NuGetServiceIndexResource(
                BASE_URL + "/v3/autocomplete",
                "SearchAutocompleteService/3.0.0-beta",
                "Package autocomplete service"));
  }

  @Test
  @DisplayName("does not advertise the invented PackageDelete/2.0.0 type")
  void doesNotAdvertisePackageDelete() {
    final var types =
        NuGetServiceIndexResources.build(BASE_URL).stream()
            .map(NuGetServiceIndexResource::type)
            .toList();

    assertThat(types).doesNotContain("PackageDelete/2.0.0");
  }

  @Test
  @DisplayName("registration, search and autocomplete each advertise a type NuGet.Client resolves")
  void advertisesATypeTheClientResolves() {
    final var resources = NuGetServiceIndexResources.build(BASE_URL);

    assertThat(typesOf(resources, BASE_URL + "/v3/registration"))
        .as("RegistrationsBaseUrl @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .containsAnyElementsOf(KNOWN_REGISTRATIONS_BASE_URL_TYPES);
    assertThat(typesOf(resources, BASE_URL + "/v3/search"))
        .as("SearchQueryService @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .containsAnyElementsOf(KNOWN_SEARCH_QUERY_SERVICE_TYPES);
    assertThat(typesOf(resources, BASE_URL + "/v3/autocomplete"))
        .as("SearchAutocompleteService @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .containsAnyElementsOf(KNOWN_SEARCH_AUTOCOMPLETE_SERVICE_TYPES);
  }

  @Test
  @DisplayName("keeps the bare search types for clients that follow the service-index docs")
  void keepsTheBareSearchTypes() {
    final var resources = NuGetServiceIndexResources.build(BASE_URL);

    assertThat(typesOf(resources, BASE_URL + "/v3/search")).contains("SearchQueryService");
    assertThat(typesOf(resources, BASE_URL + "/v3/autocomplete"))
        .contains("SearchAutocompleteService");
  }

  /** Every {@code @type} served under {@code id}. */
  private static List<String> typesOf(
      final List<NuGetServiceIndexResource> resources, final String id) {
    return resources.stream()
        .filter(resource -> resource.id().equals(id))
        .map(NuGetServiceIndexResource::type)
        .toList();
  }

  @Test
  @DisplayName(
      "does not advertise RegistrationsBaseUrl/3.6.0, which claims SemVer2 registration semantics")
  void doesNotClaimTheVersion36RegistrationSemantics() {
    final var types =
        NuGetServiceIndexResources.build(BASE_URL).stream()
            .map(NuGetServiceIndexResource::type)
            .toList();

    assertThat(types).doesNotContain("RegistrationsBaseUrl/3.6.0");
  }

  @Test
  @DisplayName("builds every @id from the given base URL")
  void buildsIdsFromTheBaseUrl() {
    final var resources = NuGetServiceIndexResources.build(BASE_URL);

    assertThat(resources)
        .extracting(NuGetServiceIndexResource::id)
        .containsExactly(
            BASE_URL + "/v3/package",
            BASE_URL + "/v3/package",
            BASE_URL + "/v3/registration",
            BASE_URL + "/v3/search",
            BASE_URL + "/v3/search",
            BASE_URL + "/v3/autocomplete",
            BASE_URL + "/v3/autocomplete");
  }
}
