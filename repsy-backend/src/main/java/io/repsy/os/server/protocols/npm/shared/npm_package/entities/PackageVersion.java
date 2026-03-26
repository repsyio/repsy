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
package io.repsy.os.server.protocols.npm.shared.npm_package.entities;

import io.repsy.core.uuidv7.UuidV7;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.NonNull;

@Data
@Entity
@Table(name = "npm_package_version")
@NoArgsConstructor
@ToString(exclude = {"npmPackage", "packageKeywords", "packageDistTags", "packageMaintainers"})
@EqualsAndHashCode(
    exclude = {"npmPackage", "packageKeywords", "packageDistTags", "packageMaintainers"})
public class PackageVersion {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "package_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private NpmPackage npmPackage;

  @Column(name = "version", nullable = false)
  private String version;

  @Column(name = "author_name")
  private String authorName;

  @Column(name = "author_email")
  private String authorEmail;

  @Column(name = "author_url")
  private String authorUrl;

  @Column(name = "bugs_url")
  private String bugsUrl;

  @Column(name = "bugs_email")
  private String bugsEmail;

  @Column(name = "description")
  private String description;

  @Column(name = "homepage")
  private String homepage;

  @Column(name = "license")
  private String license;

  @Column(name = "repository_type")
  private String repositoryType;

  @Column(name = "repository_url")
  private String repositoryUrl;

  @Column(name = "deprecated")
  private boolean deprecated;

  @Column(name = "deprecation_message")
  private String deprecationMessage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "packageVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<PackageKeyword> packageKeywords = new HashSet<>();

  @OneToMany(mappedBy = "packageVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<PackageDistTag> packageDistTags = new HashSet<>();

  @OneToMany(mappedBy = "packageVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<PackageMaintainer> packageMaintainers = new HashSet<>();
}
