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
package io.repsy.os.server.protocols.docker.shared.cleanup.entities;

import io.repsy.os.shared.repo.entities.Repo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * The cleanup policy of a Docker repo (RPS-1882, ported from Repsy Cloud). One row per repo,
 * created disabled on first read: nothing is deleted until a policy is enabled.
 */
@Entity
@Table(name = "docker_cleanup_policy")
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "repo")
public class CleanupPolicy {

  @Id
  @Column(name = "repo_id", columnDefinition = "uuid", nullable = false, updatable = false)
  private UUID id;

  @OnDelete(action = OnDeleteAction.CASCADE)
  @OneToOne(fetch = FetchType.LAZY)
  @MapsId
  @JoinColumn(name = "repo_id", nullable = false)
  private Repo repo;

  @Enumerated(EnumType.STRING)
  @Column(name = "cadence", nullable = false, length = 32)
  private CleanupCadence cadence;

  @Column(name = "name_regex", columnDefinition = "text", nullable = false)
  private String nameRegex = ".*";

  @Column(name = "name_regex_keep", columnDefinition = "text")
  private String nameRegexKeep;

  @Column(name = "keep_last_n", nullable = false)
  private int keepLastN;

  @Column(name = "keep_days", nullable = false)
  private int keepDays;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "next_run_at")
  @Nullable
  private Instant nextRunAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
