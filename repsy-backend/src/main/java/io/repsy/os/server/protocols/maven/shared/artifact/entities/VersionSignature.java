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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A stored file of a version whose detached PGP signature was verified (RPS-1188). {@code fileName}
 * is the name of the signed file ({@code lib-1.0-sources.jar}), not of its {@code .asc}. A version
 * of a repo that verifies every signature is signed when every signable file of it has a row.
 */
@Data
@Entity
@Table(name = "maven_version_signature")
@NoArgsConstructor
@ToString(exclude = {"artifactVersion"})
public class VersionSignature {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "artifact_version_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ArtifactVersion artifactVersion;

  @Column(name = "file_name", nullable = false, length = 1024)
  private String fileName;

  @Column(name = "verified_at", nullable = false)
  private Instant verifiedAt;

  /**
   * Identifier-based equality: two rows are equal when they are the same instance or carry the same
   * non-null id. {@code getId()} is used on both sides so a Hibernate proxy is compared by its real
   * id.
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof final VersionSignature other)) {
      return false;
    }

    return this.getId() != null && Objects.equals(this.getId(), other.getId());
  }

  /** Constant on purpose, like {@link VersionLicense#hashCode()}: the id is assigned on persist. */
  @Override
  public int hashCode() {
    return VersionSignature.class.hashCode();
  }
}
