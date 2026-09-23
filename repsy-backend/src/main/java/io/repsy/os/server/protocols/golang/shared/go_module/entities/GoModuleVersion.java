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
package io.repsy.os.server.protocols.golang.shared.go_module.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

@Data
@Entity
@Table(name = "go_module_version")
@NoArgsConstructor
@ToString(exclude = "goModule")
public class GoModuleVersion {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "module_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private GoModule goModule;

  @Column(name = "version", nullable = false, length = GoVersionUtils.MAX_VERSION_LENGTH)
  private String version;

  /** The Go toolchain version declared in go.mod (e.g. "1.21"). Null until .mod is uploaded. */
  @Column(name = "go_version", length = GoVersionUtils.MAX_GO_VERSION_LENGTH)
  private @Nullable String goVersion;

  /** h1: hash of the uploaded go.mod file. Null until .mod is uploaded. */
  @Column(name = "mod_hash", length = 100)
  private @Nullable String modHash;

  /** h1: hash of the uploaded zip file. Null until .zip is uploaded. */
  @Column(name = "zip_hash", length = 100)
  private @Nullable String zipHash;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * Identifier-based equality: two go module versions are equal when they are the same instance or
   * carry the same non-null id. One that has not been persisted yet has no id and equals only
   * itself. {@code getId()} is used on both sides so a Hibernate proxy is compared by its real id.
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof final GoModuleVersion other)) {
      return false;
    }

    return this.getId() != null && Objects.equals(this.getId(), other.getId());
  }

  /**
   * Constant on purpose: the id is assigned on persist and the other columns can change on flush,
   * so a hash derived from them would move a go module version held in a {@code HashSet} into the
   * wrong bucket. It also keeps the lazy associations out of the hash.
   */
  @Override
  public int hashCode() {
    return GoModuleVersion.class.hashCode();
  }
}
