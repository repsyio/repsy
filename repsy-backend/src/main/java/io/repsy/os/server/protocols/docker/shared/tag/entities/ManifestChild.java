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

import io.repsy.protocols.docker.shared.utils.DockerConstants;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * An edge of an index (manifest list): the {@code parent} index references the {@code child}
 * manifest, which the index names under {@code platform}. Both ends are manifests of the same
 * image; deleting either of them deletes the edge.
 */
@Getter
@Setter
@Entity
@Table(name = "docker_manifest_child")
@NoArgsConstructor
@ToString(exclude = {"parent", "child"})
public class ManifestChild {

  @EmbeddedId private ManifestChildId id = new ManifestChildId();

  @MapsId("parentId")
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Manifest parent;

  @MapsId("childId")
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "child_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Manifest child;

  @Column(name = "platform", nullable = false, length = DockerConstants.MAX_PLATFORM_LENGTH)
  private String platform;

  public ManifestChild(final Manifest parent, final Manifest child, final String platform) {

    this.id = new ManifestChildId(parent.getId(), child.getId());
    this.parent = parent;
    this.child = child;
    this.platform = platform;
  }

  /** Equality by the composite key, so a proxy compares like the entity it stands for. */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof final ManifestChild other)) {
      return false;
    }

    return this.getId() != null && Objects.equals(this.getId(), other.getId());
  }

  @Override
  public int hashCode() {
    return ManifestChild.class.hashCode();
  }
}
