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
package io.repsy.os.server.protocols.docker.shared.tag.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.NonNull;

@Data
@Entity
@Table(name = "docker_manifest")
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"layers", "tagPlatform"})
@EqualsAndHashCode(exclude = {"layers", "tagPlatform"})
public class Manifest {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Version
  @Column(nullable = false)
  private Integer version;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "platform", nullable = false)
  private String platform;

  @Column(name = "digest", nullable = false)
  private String digest;

  @Column(name = "media_type", nullable = false)
  private String mediaType;

  @Column(name = "config_media_type")
  private String configMediaType;

  @Column(name = "config_digest")
  private String configDigest;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "config_size")
  private long configSize;

  @Column(name = "created_at")
  @CreationTimestamp
  private Instant createdAt;

  @Column(name = "last_updated_at")
  @UpdateTimestamp
  private Instant lastUpdatedAt;

  @ManyToMany
  @JoinTable(
      name = "docker_manifest_layer",
      joinColumns = @JoinColumn(name = "manifest_id"),
      inverseJoinColumns = @JoinColumn(name = "layer_id"))
  private @NonNull Set<Layer> layers = new HashSet<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tag_platform_id")
  @OnDelete(action = OnDeleteAction.CASCADE)
  private TagPlatform tagPlatform;
}
