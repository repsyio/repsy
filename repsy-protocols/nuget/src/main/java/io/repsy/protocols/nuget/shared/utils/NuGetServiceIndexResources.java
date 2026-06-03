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

  public static List<NuGetServiceIndexResource> build(final String baseUrl) {

    return List.of(
        new NuGetServiceIndexResource(
            baseUrl + "/v3/package", "PackageBaseAddress/3.0.0", "Package download base URL"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/package", "PackagePublish/2.0.0", "Package publish endpoint"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/registration",
            "RegistrationsBaseUrl/3.0.0",
            "Package registration base URL"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/search", "SearchQueryService/3.0.0", "Package search service"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/autocomplete",
            "SearchAutocompleteService/3.0.0",
            "Package autocomplete service"),
        new NuGetServiceIndexResource(
            baseUrl + "/v3/package", "PackageDelete/2.0.0", "Package delete/unlist endpoint"));
  }
}
