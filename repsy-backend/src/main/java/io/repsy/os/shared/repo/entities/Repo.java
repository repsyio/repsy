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
package io.repsy.os.shared.repo.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * {@code @DynamicUpdate} keeps an UPDATE to the columns that changed. Without it a full-row save
 * (for example a settings change) also writes the {@code disk_usage} read at load time, which loses
 * a usage increment that committed in between (RPS-2116). The counter is changed only by the single
 * guarded UPDATE of {@code RepoRepository}, never by a setter and save.
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "repo")
@NoArgsConstructor
public class Repo {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 25)
  private String name;

  @Column(name = "description", length = 500)
  private @Nullable String description;

  @Column(name = "private_repo", nullable = false)
  private boolean privateRepo;

  @Enumerated(EnumType.STRING)
  @Column(name = "type")
  private @Nullable RepoType type;

  @Column(name = "allow_override", nullable = false)
  private boolean allowOverride;

  @Column(name = "snapshots")
  private @Nullable Boolean snapshots;

  @Column(name = "releases")
  private @Nullable Boolean releases;

  @Column(name = "security_scan_enabled", nullable = false)
  private boolean securityScanEnabled;

  /** Maven only (RPS-1188): verify every artifact {@code .asc}, not only the {@code .pom.asc}. */
  @Column(name = "pgp_verify_all_signatures_enabled", nullable = false)
  private boolean pgpVerifyAllSignaturesEnabled;

  /** Maven only (RPS-1204): ask public key servers for a signer's key that is not registered. */
  @Column(name = "pgp_key_server_lookup_enabled", nullable = false)
  private boolean pgpKeyServerLookupEnabled = true;

  @Column(name = "disk_usage", nullable = false)
  private long diskUsage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
