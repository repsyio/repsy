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
package io.repsy.os.server.protocols.cargo.shared.crate.mappers;

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoAuthor;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCategory;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoKeyword;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@NullMarked
@RequiredArgsConstructor
public class CargoJsonConverter {

  private final ObjectMapper objectMapper;

  @Named("authorsToStrings")
  public List<String> authorsToStrings(final @Nullable Set<CargoAuthor> authors) {

    if (authors == null) {
      return List.of();
    }

    return authors.stream().map(CargoAuthor::getAuthor).toList();
  }

  @Named("keywordsToStrings")
  public List<String> keywordsToStrings(final @Nullable Set<CargoKeyword> keywords) {

    if (keywords == null) {
      return List.of();
    }

    return keywords.stream().map(CargoKeyword::getKeyword).toList();
  }

  @Named("categoriesToStrings")
  public List<String> categoriesToStrings(final @Nullable Set<CargoCategory> categories) {

    if (categories == null) {
      return List.of();
    }

    return categories.stream().map(CargoCategory::getCategory).toList();
  }

  @Named("jsonToDeps")
  public List<CrateIndexDep> jsonToDeps(final @Nullable String json) {

    try {
      if (json == null) {
        return List.of();
      }

      return this.objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JacksonIOException e) {
      return List.of();
    }
  }

  @Named("jsonToFeatures")
  public Map<String, List<String>> jsonToFeatures(final @Nullable String json) {

    try {
      if (json == null) {
        return Collections.emptyMap();
      }

      return this.objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JacksonIOException e) {
      return Collections.emptyMap();
    }
  }
}
