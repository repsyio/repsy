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
 * RPS-1213: {@code NuGetServiceIndexResources.build} used to advertise {@code @type} strings (e.g.
 * {@code RegistrationsBaseUrl/3.0.0}, {@code SearchQueryService/3.0.0}) that NuGet.Client does not
 * recognise, plus an invented {@code PackageDelete/2.0.0} type. The client matches {@code @type} by
 * exact string against a fixed vocabulary, so an unrecognised type is silently treated as "resource
 * absent" -- which made {@code dotnet package search} and friends fail with "The source does not
 * have a Search service!" even though the underlying routes worked.
 */
class NuGetServiceIndexResourcesTest {

  /**
   * NuGet.Client's recognised {@code @type} vocabulary for these three resource kinds, per
   * NuGet.Client's {@code ServiceTypes.cs}
   * (https://github.com/NuGet/NuGet.Client/blob/dev/src/NuGet.Core/NuGet.Protocol/Resources/ServiceTypes.cs)
   * and the official service-index docs
   * (https://learn.microsoft.com/en-us/nuget/api/service-index#resources). A type not in this set
   * is invisible to the client, even though the docs describe it as free-form-looking versioned
   * strings.
   */
  private static final Set<String> KNOWN_REGISTRATIONS_BASE_URL_TYPES =
      Set.of(
          "RegistrationsBaseUrl",
          "RegistrationsBaseUrl/3.0.0-beta",
          "RegistrationsBaseUrl/3.0.0-rc",
          "RegistrationsBaseUrl/3.4.0",
          "RegistrationsBaseUrl/3.6.0");

  private static final Set<String> KNOWN_SEARCH_QUERY_SERVICE_TYPES =
      Set.of("SearchQueryService", "SearchQueryService/3.0.0-beta", "SearchQueryService/3.4.0");

  private static final Set<String> KNOWN_SEARCH_AUTOCOMPLETE_SERVICE_TYPES =
      Set.of("SearchAutocompleteService", "SearchAutocompleteService/3.0.0-beta");

  private static final String BASE_URL = "https://repsy.example/some-repo";

  @Test
  @DisplayName("serves exactly the six-resource shape, dropping the invented PackageDelete type")
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
                BASE_URL + "/v3/autocomplete",
                "SearchAutocompleteService",
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
  @DisplayName(
      "registration, search and autocomplete each advertise a type NuGet.Client recognises")
  void advertisesOnlyRecognisedTypes() {
    final var resources = NuGetServiceIndexResources.build(BASE_URL);

    assertThat(KNOWN_REGISTRATIONS_BASE_URL_TYPES)
        .as("RegistrationsBaseUrl @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .contains(typeOf(resources, BASE_URL + "/v3/registration"));
    assertThat(KNOWN_SEARCH_QUERY_SERVICE_TYPES)
        .as("SearchQueryService @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .contains(typeOf(resources, BASE_URL + "/v3/search"));
    assertThat(KNOWN_SEARCH_AUTOCOMPLETE_SERVICE_TYPES)
        .as("SearchAutocompleteService @type must be one NuGet.Client's ServiceTypes.cs recognises")
        .contains(typeOf(resources, BASE_URL + "/v3/autocomplete"));
  }

  /**
   * Looks up the {@code @type} of the one resource served under {@code id}. Only safe for the
   * registration/search/autocomplete ids, which are each served once; {@code /v3/package} is served
   * twice (base address and publish) and is never looked up here.
   */
  private static String typeOf(final List<NuGetServiceIndexResource> resources, final String id) {
    return resources.stream()
        .filter(resource -> resource.id().equals(id))
        .findFirst()
        .orElseThrow()
        .type();
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
            BASE_URL + "/v3/autocomplete");
  }
}
