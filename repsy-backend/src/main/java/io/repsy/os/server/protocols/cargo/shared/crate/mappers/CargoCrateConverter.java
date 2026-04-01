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
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateMeta;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoKeyword;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Mapper(componentModel = "spring")
public abstract class CargoCrateConverter {

  @Autowired private ObjectMapper objectMapper;

  @Mapping(target = "authors", source = "authors", qualifiedByName = "authorsToStrings")
  @Mapping(target = "keywords", source = "keywords", qualifiedByName = "keywordsToStrings")
  @Mapping(target = "categories", source = "categories", qualifiedByName = "categoriesToStrings")
  @Mapping(target = "originalName", source = "originalName")
  @Mapping(target = "maxVersion", source = "maxVersion")
  @Mapping(target = "totalDownloads", source = "totalDownloads")
  public abstract CrateInfo toCrateInfo(CargoCrate crate);

  @Mapping(target = "crateId", source = "meta.crate.id")
  @Mapping(target = "name", source = "meta.crate.name")
  @Mapping(target = "version", source = "meta.version")
  @Mapping(target = "readme", source = "meta.readme")
  @Mapping(target = "license", source = "meta.license")
  @Mapping(target = "licenseFile", source = "meta.licenseFile")
  @Mapping(target = "documentation", source = "meta.documentation")
  @Mapping(target = "edition", source = "meta.edition")
  @Mapping(target = "rustVersion", source = "meta.rustVersion")
  @Mapping(target = "deps", source = "index.deps", qualifiedByName = "jsonToDeps")
  @Mapping(target = "downloads", source = "meta.downloads")
  @Mapping(target = "createdAt", source = "meta.createdAt")
  public abstract CrateVersionInfo toCrateVersionInfo(
      CargoCrate crate, CargoCrateMeta meta, CargoCrateIndex index);

  @Mapping(target = "name", source = "name")
  @Mapping(target = "vers", source = "vers")
  @Mapping(target = "deps", source = "deps", qualifiedByName = "jsonToDeps")
  @Mapping(target = "cksum", source = "cksum")
  @Mapping(target = "features", source = "features", qualifiedByName = "jsonToFeatures")
  @Mapping(target = "yanked", source = "yanked")
  @Mapping(target = "links", source = "links")
  @Mapping(target = "v", source = "v")
  @Mapping(target = "features2", source = "features2", qualifiedByName = "jsonToFeatures")
  @Mapping(target = "rustVersion", source = "rustVersion")
  public abstract CrateIndexEntry toCrateIndexEntry(CargoCrateIndex index);

  @Named("authorsToStrings")
  protected List<String> authorsToStrings(final Set<CargoAuthor> authors) {
    if (authors == null) {
      return Collections.emptyList();
    }
    return authors.stream().map(CargoAuthor::getAuthor).toList();
  }

  @Named("keywordsToStrings")
  protected List<String> keywordsToStrings(final Set<CargoKeyword> keywords) {
    if (keywords == null) {
      return Collections.emptyList();
    }

    return keywords.stream().map(CargoKeyword::getKeyword).toList();
  }

  @Named("categoriesToStrings")
  protected List<String> categoriesToStrings(final Set<CargoCategory> categories) {
    if (categories == null) {
      return Collections.emptyList();
    }

    return categories.stream().map(CargoCategory::getCategory).toList();
  }

  @Named("jsonToDeps")
  protected List<CrateIndexDep> jsonToDeps(final String json) {
    if (json == null) {
      return List.of();
    }

    try {
      return this.objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JacksonIOException e) {
      return List.of();
    }
  }

  @Named("jsonToFeatures")
  protected Map<String, List<String>> jsonToFeatures(final String json) {
    if (json == null) {
      return Collections.emptyMap();
    }

    try {
      return this.objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JacksonIOException e) {
      return Collections.emptyMap();
    }
  }
}
