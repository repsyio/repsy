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
package io.repsy.os.server.shared.token.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.shared.repo.entities.Repo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "repo_deploy_token")
@Data
@NoArgsConstructor
@EqualsAndHashCode
public class RepoDeployToken {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "repo_id", nullable = false)
  private Repo repo;

  @Column(name = "username", nullable = false, length = 80)
  private String username;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "token", nullable = false, length = 128)
  private String token;

  @Column(name = "read_only", nullable = false)
  private boolean readOnly;

  @Column(name = "expiration_date")
  private Instant expirationDate;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "token_duration")
  private int tokenDurationDay;
}
