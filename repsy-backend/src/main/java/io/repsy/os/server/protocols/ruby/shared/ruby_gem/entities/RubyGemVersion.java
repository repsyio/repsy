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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities;

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
import org.jspecify.annotations.Nullable;

@Data
@Entity
@Table(name = "ruby_gem_version")
@NoArgsConstructor
@ToString(exclude = {"gem", "dependencies"})
@EqualsAndHashCode(exclude = {"gem", "dependencies"})
public class RubyGemVersion {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "gem_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RubyGem gem;

  @Column(name = "version", nullable = false, length = 64)
  private String version;

  @Column(name = "platform", nullable = false, length = 64)
  private String platform;

  @Column(name = "checksum", nullable = false, length = 64)
  private String checksum;

  @Column(name = "authors", length = 512)
  private @Nullable String authors;

  @Column(name = "description", columnDefinition = "TEXT")
  private @Nullable String description;

  @Column(name = "homepage", length = 512)
  private @Nullable String homepage;

  @Column(name = "required_ruby_version", length = 64)
  private @Nullable String requiredRubyVersion;

  @Column(name = "yanked", nullable = false)
  private boolean yanked;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "gemVersion", cascade = CascadeType.ALL, orphanRemoval = true)
  private @NonNull Set<RubyGemDependency> dependencies = new HashSet<>();
}
