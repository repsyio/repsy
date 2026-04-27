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
package io.repsy.os.server.protocols.maven.shared.artifact.mappers;

import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.generated.model.VersionDeveloperInfo;
import io.repsy.os.generated.model.VersionLicenseInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionDeveloper;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionLicense;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ArtifactConverter {

  default Instant map(final LocalDateTime localDateTime) {
    return localDateTime != null ? localDateTime.toInstant(ZoneOffset.UTC) : null;
  }

  default ArtifactVersionInfo.TypeEnum map(final ArtifactVersionType type) {
    return type != null ? ArtifactVersionInfo.TypeEnum.valueOf(type.name()) : null;
  }

  VersionDeveloperInfo toDto(VersionDeveloper developer);

  VersionLicenseInfo toDto(VersionLicense license);

  @Mapping(target = "id", source = "version.id")
  @Mapping(target = "name", source = "version.name")
  @Mapping(target = "prefix", source = "version.prefix")
  @Mapping(target = "packaging", source = "version.packaging")
  @Mapping(target = "createdAt", source = "version.createdAt")
  @Mapping(target = "lastUpdatedAt", source = "version.lastUpdatedAt")
  @Mapping(target = "artifactName", source = "artifact.artifactName")
  @Mapping(target = "artifactGroupName", source = "artifact.groupName")
  @Mapping(target = "artifactVersionName", source = "artifact.latest")
  @Mapping(target = "scmUrl", source = "version.sourceCodeUrl")
  @Mapping(target = "developers", source = "version.versionDevelopers")
  @Mapping(target = "licenses", source = "version.versionLicenses")
  @Mapping(target = "pomFile", ignore = true)
  ArtifactVersionInfo toArtifactVersionInfo(Artifact artifact, ArtifactVersion version);

  @Mapping(target = "lastUpdatedAt", source = "lastUpdatedAt")
  io.repsy.os.generated.model.ArtifactListItem toArtifactListItemDto(ArtifactListItem source);

  @Mapping(target = "lastUpdatedAt", source = "lastUpdatedAt")
  io.repsy.os.generated.model.ArtifactVersionListItem toArtifactVersionListItemDto(
      ArtifactVersionListItem source);

  io.repsy.os.generated.model.KeyStoreItem toKeyStoreItemDto(KeyStoreItem source);
}
