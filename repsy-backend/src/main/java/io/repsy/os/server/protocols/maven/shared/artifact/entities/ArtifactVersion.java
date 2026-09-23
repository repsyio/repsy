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
package io.repsy.os.server.protocols.maven.shared.artifact.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.NonNull;

@Data
@Entity
@Table(name = "maven_artifact_version")
@NoArgsConstructor
public class ArtifactVersion {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "artifact_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Artifact artifact;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private ArtifactVersionType type;

  @Column(name = "version_name", nullable = false, length = 500)
  private String versionName;

  @Column(name = "name", length = MavenPublishLimits.MAX_NAME_LENGTH)
  private String name;

  @Column(name = "description", length = MavenPublishLimits.MAX_DESCRIPTION_LENGTH)
  private String description;

  @Column(name = "prefix", length = MavenPublishLimits.MAX_PREFIX_LENGTH)
  private String prefix;

  @Column(name = "url", length = MavenPublishLimits.MAX_URL_LENGTH)
  private String url;

  @Column(name = "organization", length = MavenPublishLimits.MAX_ORGANIZATION_LENGTH)
  private String organization;

  @Column(name = "packaging", length = MavenPublishLimits.MAX_PACKAGING_LENGTH)
  private String packaging;

  @Column(name = "source_code_url", length = MavenPublishLimits.MAX_URL_LENGTH)
  private String sourceCodeUrl;

  @Column(name = "parent_artifact_name", length = MavenPublishLimits.MAX_PARENT_COORDINATE_LENGTH)
  private String parentArtifactName;

  @Column(
      name = "parent_artifact_version",
      length = MavenPublishLimits.MAX_PARENT_COORDINATE_LENGTH)
  private String parentArtifactVersion;

  @Column(name = "parent_artifact_group", length = MavenPublishLimits.MAX_PARENT_COORDINATE_LENGTH)
  private String parentArtifactGroup;

  @Column(name = "has_documents", nullable = false)
  private boolean hasDocuments;

  @Column(name = "has_sources", nullable = false)
  private boolean hasSources;

  @Column(name = "has_modules", nullable = false)
  private boolean hasModules;

  @Column(name = "signed")
  private boolean signed;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "last_updated_at")
  private Instant lastUpdatedAt;

  @OneToMany(mappedBy = "artifactVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<VersionLicense> versionLicenses = new HashSet<>();

  @OneToMany(mappedBy = "artifactVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<VersionDeveloper> versionDevelopers = new HashSet<>();
}
