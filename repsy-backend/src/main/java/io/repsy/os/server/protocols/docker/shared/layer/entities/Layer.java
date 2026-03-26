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
package io.repsy.os.server.protocols.docker.shared.layer.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.shared.repo.entities.Repo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.NonNull;

@Data
@Entity
@Table(name = "docker_layer")
@NoArgsConstructor
public class Layer {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "repo_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Repo repo;

  @ManyToMany(mappedBy = "layers", fetch = FetchType.LAZY)
  private @NonNull Set<Manifest> manifests = new HashSet<>();

  @Column(name = "digest", nullable = false)
  private String digest;

  @Column(name = "size", nullable = false)
  private long size;

  @Column(name = "media_type", nullable = false)
  private String mediaType;

  @Column(name = "created_at")
  @CreationTimestamp
  private Instant createdAt;

  @Column(name = "last_updated_at")
  @UpdateTimestamp
  private Instant lastUpdatedAt;

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof final Layer layer)) {
      return false;
    }

    return this.id == layer.id && Objects.equals(this.digest, layer.digest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.id, this.digest);
  }
}
