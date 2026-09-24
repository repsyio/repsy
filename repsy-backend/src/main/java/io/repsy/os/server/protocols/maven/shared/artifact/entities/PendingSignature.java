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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A detached signature ({@code .asc}) that reached a repo verifying every signature before the file
 * it signs could be checked against it (RPS-1188). It is kept here, unverified and out of storage,
 * keyed by the repo and the repo-relative path of the FILE it signs ({@code
 * com/acme/lib/1.0/lib-1.0-javadoc.jar}), and is verified when that file arrives, or when the POM
 * registers the version. A version row need not exist yet, so it is not referenced.
 */
@Data
@Entity
@Table(name = "maven_pending_signature")
@NoArgsConstructor
public class PendingSignature {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Column(name = "repo_id", columnDefinition = "uuid", nullable = false)
  private UUID repoId;

  @Column(name = "signed_file_path", nullable = false, length = 2048)
  private String signedFilePath;

  @Column(name = "armored_signature", nullable = false, columnDefinition = "text")
  private String armoredSignature;

  @Column(name = "key_id", nullable = false, length = 16)
  private String keyId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Identifier-based equality, like {@link VersionSignature}. */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof final PendingSignature other)) {
      return false;
    }

    return this.getId() != null && Objects.equals(this.getId(), other.getId());
  }

  /**
   * Constant on purpose, like {@link VersionSignature#hashCode()}: the id is assigned on persist.
   */
  @Override
  public int hashCode() {
    return PendingSignature.class.hashCode();
  }
}
