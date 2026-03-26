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

import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.VersionDeveloperInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.VersionLicenseInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ArtifactConverter {

  default @NonNull ArtifactVersionInfo toArtifactVersionInfo(
      final @NonNull Artifact artifact,
      final @NonNull ArtifactVersion version,
      final List<VersionDeveloperInfo> developers,
      final List<VersionLicenseInfo> licenses) {

    return ArtifactVersionInfo.builder()
        .name(version.getName())
        .versionName(version.getVersionName())
        .artifactName(artifact.getArtifactName())
        .artifactGroupName(artifact.getGroupName())
        .artifactVersionName(artifact.getLatest())
        .description(version.getDescription())
        .prefix(version.getPrefix())
        .url(version.getUrl())
        .organization(version.getOrganization())
        .packaging(version.getPackaging())
        .sourceCodeUrl(version.getSourceCodeUrl())
        .hasDocuments(version.isHasDocuments())
        .hasModules(version.isHasModules())
        .hasSources(version.isHasSources())
        .type(version.getType())
        .createdAt(version.getCreatedAt())
        .lastUpdatedAt(version.getLastUpdatedAt())
        .scmUrl(version.getSourceCodeUrl())
        .developers(developers)
        .licenses(licenses)
        .build();
  }
}
