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
package io.repsy.os.server.protocols.maven.shared.artifact.dtos;

import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ArtifactVersionInfo {
  private UUID id;
  private String name;
  private String versionName;
  private String artifactName;
  private String artifactGroupName;
  private String artifactVersionName;
  private String description;
  private String prefix;
  private String url;
  private String organization;
  private String packaging;
  private String sourceCodeUrl;
  private ArtifactVersionType type;
  private boolean hasDocuments;
  private boolean hasSources;
  private boolean hasModules;
  private Instant createdAt;
  private Instant lastUpdatedAt;
  private String pomFile;
  private List<VersionLicenseInfo> licenses;
  private String scmUrl;
  private List<VersionDeveloperInfo> developers;
  private boolean signed;
}
