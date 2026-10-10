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

import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationPageItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** Builds the registration pages of the NuGet registration index. */
@NullMarked
@UtilityClass
public final class NuGetRegistrationPageUtils {

  public static final String FORMAT_JSON = ".json";
  public static final Map<String, String> NUGET_CONTEXT =
      Map.of(
          "@vocab", "http://schema.nuget.org/schema#",
          "comment", "http://www.w3.org/2000/01/rdf-schema#comment");
  private static final int REGISTRATION_PAGE_SIZE = 64;

  public static List<NuGetRegistrationPageItem> buildRegistrationPages(
      final List<NuGetRegistrationLeafItem> leafItems, final String indexUrl) {

    if (leafItems.size() <= REGISTRATION_PAGE_SIZE) {
      final var lowerVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .min(NuGetVersionUtils.VERSION_COMPARATOR)
              .orElse("");
      final var upperVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .max(NuGetVersionUtils.VERSION_COMPARATOR)
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
      final var pageUrl = indexUrl.replace("/index.json", "/page/" + pageIndex + FORMAT_JSON);
      final var lowerVersion =
          chunk.stream()
              .map(i -> i.catalogEntry().version())
              .min(NuGetVersionUtils.VERSION_COMPARATOR)
              .orElse("");
      final var upperVersion =
          chunk.stream()
              .map(i -> i.catalogEntry().version())
              .max(NuGetVersionUtils.VERSION_COMPARATOR)
              .orElse("");
      pages.add(
          new NuGetRegistrationPageItem(
              pageUrl, "catalog:CatalogPage", chunk.size(), chunk, lowerVersion, upperVersion));
      pageIndex++;
    }
    return pages;
  }
}
