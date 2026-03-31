package io.repsy.os.server.protocols.cargo.protocol.shared.crate.mappers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoAuthor;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCategory;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrateIndex;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrateMeta;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoKeyword;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

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
  @Mapping(target = "downloads", source = "meta.downloads")
  @Mapping(target = "createdAt", source = "meta.createdAt")
  public abstract CrateVersionInfo toCrateVersionInfo(CargoCrate crate, CargoCrateMeta meta);

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
  protected List<String> authorsToStrings(java.util.Set<CargoAuthor> authors) {
    if (authors == null) return Collections.emptyList();
    return authors.stream().map(CargoAuthor::getAuthor).toList();
  }

  @Named("keywordsToStrings")
  protected List<String> keywordsToStrings(java.util.Set<CargoKeyword> keywords) {
    if (keywords == null) return Collections.emptyList();
    return keywords.stream().map(CargoKeyword::getKeyword).toList();
  }

  @Named("categoriesToStrings")
  protected List<String> categoriesToStrings(java.util.Set<CargoCategory> categories) {
    if (categories == null) return Collections.emptyList();
    return categories.stream().map(CargoCategory::getCategory).toList();
  }

  @Named("jsonToDeps")
  protected List<CrateIndexDep> jsonToDeps(String json) {
    if (json == null) return Collections.emptyList();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return Collections.emptyList();
    }
  }

  @Named("jsonToFeatures")
  protected Map<String, List<String>> jsonToFeatures(String json) {
    if (json == null) return Collections.emptyMap();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return Collections.emptyMap();
    }
  }
}
