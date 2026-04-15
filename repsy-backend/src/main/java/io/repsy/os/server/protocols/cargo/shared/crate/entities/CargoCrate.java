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
package io.repsy.os.server.protocols.cargo.shared.crate.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.shared.repo.entities.Repo;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Data
@Entity
@Table(name = "cargo_crate")
@NoArgsConstructor
@ToString(exclude = {"repo", "crateIndexes", "crateMetas", "authors", "keywords", "categories"})
@EqualsAndHashCode(
    exclude = {"repo", "crateIndexes", "crateMetas", "authors", "keywords", "categories"})
public class CargoCrate {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "repo_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Repo repo;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Column(name = "original_name", nullable = false, length = 64)
  private String originalName;

  @Column(name = "max_version", nullable = false, length = 64)
  private String maxVersion;

  @Column(name = "total_downloads", nullable = false)
  private long totalDownloads;

  @Column(name = "has_lib", nullable = false)
  private boolean hasLib;

  @Nullable
  @Column(name = "description")
  private String description;

  @Nullable
  @Column(name = "homepage", length = 255)
  private String homepage;

  @Nullable
  @Column(name = "repository", length = 255)
  private String repository;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_updated_at")
  private Instant lastUpdatedAt;

  @OneToMany(mappedBy = "crate", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<CargoCrateIndex> crateIndexes = new HashSet<>();

  @OneToMany(mappedBy = "crate", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<CargoCrateMeta> crateMetas = new HashSet<>();

  @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinTable(
      name = "cargo_crate_author",
      joinColumns = @JoinColumn(name = "crate_id"),
      inverseJoinColumns = @JoinColumn(name = "author_id"))
  private @NonNull Set<CargoAuthor> authors = new HashSet<>();

  @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinTable(
      name = "cargo_crate_keyword",
      joinColumns = @JoinColumn(name = "crate_id"),
      inverseJoinColumns = @JoinColumn(name = "keyword_id"))
  private @NonNull Set<CargoKeyword> keywords = new HashSet<>();

  @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinTable(
      name = "cargo_crate_category",
      joinColumns = @JoinColumn(name = "crate_id"),
      inverseJoinColumns = @JoinColumn(name = "category_id"))
  private @NonNull Set<CargoCategory> categories = new HashSet<>();
}
