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

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateMeta;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Mappings;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = CargoJsonConverter.class)
public interface CargoCrateConverter {

  @Mappings({
    @Mapping(target = "authors", source = "authors", qualifiedByName = "authorsToStrings"),
    @Mapping(target = "keywords", source = "keywords", qualifiedByName = "keywordsToStrings"),
    @Mapping(target = "categories", source = "categories", qualifiedByName = "categoriesToStrings"),
    @Mapping(target = "originalName", source = "originalName"),
    @Mapping(target = "maxVersion", source = "maxVersion"),
    @Mapping(target = "totalDownloads", source = "totalDownloads")
  })
  BaseCrateInfo<UUID> toCrateInfo(CargoCrate crate);

  @Mappings({
    @Mapping(target = "crateId", source = "meta.crate.id"),
    @Mapping(target = "name", source = "meta.crate.name"),
    @Mapping(target = "version", source = "meta.version"),
    @Mapping(target = "readme", source = "meta.readme"),
    @Mapping(target = "license", source = "meta.license"),
    @Mapping(target = "licenseFile", source = "meta.licenseFile"),
    @Mapping(target = "documentation", source = "meta.documentation"),
    @Mapping(target = "edition", source = "meta.edition"),
    @Mapping(target = "rustVersion", source = "meta.rustVersion"),
    @Mapping(target = "deps", source = "index.deps", qualifiedByName = "jsonToDeps"),
    @Mapping(target = "downloads", source = "meta.downloads"),
    @Mapping(target = "createdAt", source = "meta.createdAt")
  })
  BaseCrateVersionInfo<UUID> toCrateVersionInfo(
      CargoCrate crate, CargoCrateMeta meta, CargoCrateIndex index);

  @Mappings({
    @Mapping(target = "name", source = "name"),
    @Mapping(target = "vers", source = "vers"),
    @Mapping(target = "deps", source = "deps", qualifiedByName = "jsonToDeps"),
    @Mapping(target = "cksum", source = "cksum"),
    @Mapping(target = "features", source = "features", qualifiedByName = "jsonToFeatures"),
    @Mapping(target = "yanked", source = "yanked"),
    @Mapping(target = "links", source = "links"),
    @Mapping(target = "v", source = "v"),
    @Mapping(target = "features2", source = "features2", qualifiedByName = "jsonToFeatures"),
    @Mapping(target = "rustVersion", source = "rustVersion")
  })
  CrateIndexEntry toCrateIndexEntry(CargoCrateIndex index);
}
