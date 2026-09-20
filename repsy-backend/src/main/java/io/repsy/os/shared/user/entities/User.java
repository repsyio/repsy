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
package io.repsy.os.shared.user.entities;

import io.repsy.core.uuidv7.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;

/**
 * {@code @DynamicUpdate} keeps an UPDATE to the columns that changed. The default writes every
 * column, so two transactions that changed different columns of one row (a password change and the
 * async last-login update, RPS-1032) would each write the other's column back with the value they
 * read.
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "users")
@RequiredArgsConstructor
public class User {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Column(name = "username", nullable = false, length = 25)
  private String username;

  @Column(name = "hash", nullable = false, length = 128)
  private String hash;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private UserRole role;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  /** Embedded in refresh tokens; bumping it revokes every refresh token issued before. */
  @Column(name = "token_version", nullable = false)
  private int tokenVersion;

  public void revokeRefreshTokens() {
    this.tokenVersion++;
  }
}
