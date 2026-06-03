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
package io.repsy.os.server.protocols.nuget.shared.packages.entities;

import io.repsy.core.uuidv7.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

@Data
@Entity
@Table(name = "nuget_package_version")
@NoArgsConstructor
@ToString(exclude = {"nugetPackage"})
@EqualsAndHashCode(exclude = {"nugetPackage"})
public class NuGetPackageVersion {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "package_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private NuGetPackage nugetPackage;

  @Column(name = "version", nullable = false, length = 64)
  private String version;

  @Column(name = "is_prerelease", nullable = false)
  private boolean isPrerelease;

  @Column(name = "is_listed", nullable = false)
  private boolean isListed;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;

  @Column(name = "download_count", nullable = false)
  private long downloadCount;

  @Nullable
  @Column(name = "title", length = 256)
  private String title;

  @Nullable
  @Column(name = "description", columnDefinition = "clob")
  private String description;

  @Nullable
  @Column(name = "authors", length = 1024)
  private String authors;

  @Nullable
  @Column(name = "tags", length = 512)
  private String tags;

  @Nullable
  @Column(name = "icon_url", length = 512)
  private String iconUrl;

  @Nullable
  @Column(name = "license_url", length = 512)
  private String licenseUrl;

  @Nullable
  @Column(name = "project_url", length = 512)
  private String projectUrl;

  @Nullable
  @Column(name = "repository_url", length = 512)
  private String repositoryUrl;

  @Nullable
  @Column(name = "readme", columnDefinition = "clob")
  private String readme;

  @Nullable
  @Column(name = "dependencies", columnDefinition = "clob")
  private String dependencies;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
