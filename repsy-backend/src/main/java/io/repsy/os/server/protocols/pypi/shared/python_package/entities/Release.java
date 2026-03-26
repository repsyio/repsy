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
package io.repsy.os.server.protocols.pypi.shared.python_package.entities;

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
@Table(name = "pypi_release")
@NoArgsConstructor
@ToString(exclude = {"pypiPackage", "releaseClassifiers", "releaseProjectURLS"})
@EqualsAndHashCode(exclude = {"pypiPackage", "releaseClassifiers", "releaseProjectURLS"})
public class Release {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "package_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private PypiPackage pypiPackage;

  @Column(name = "version")
  private String version;

  @Column(name = "final_release")
  private boolean finalRelease;

  @Column(name = "pre_release")
  private boolean preRelease;

  @Column(name = "post_release")
  private boolean postRelease;

  @Column(name = "dev_release")
  private boolean devRelease;

  @Column(name = "requires_python")
  private String requiresPython;

  @Column(name = "summary")
  private String summary;

  @Column(name = "home_page")
  private String homePage;

  @Column(name = "author")
  private String author;

  @Column(name = "author_email")
  private String authorEmail;

  @Column(name = "license")
  private String license;

  @Column(name = "description")
  private String description;

  @Column(name = "description_content_type")
  private String descriptionContentType;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<ReleaseClassifier> releaseClassifiers = new HashSet<>();

  @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<ReleaseProjectURL> releaseProjectURLS = new HashSet<>();
}
