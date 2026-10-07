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
package io.repsy.os.shared.token.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenScopeConverter;
import io.repsy.os.shared.user.entities.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A personal access token: a long-lived credential of a user, for CI and the Repsy CLI. Unlike a
 * deploy token it belongs to the user, not to a repository, and its {@link #scopes} can only narrow
 * what the user can do.
 *
 * <p>Only the hash of the secret is stored ({@code TokenHash}); the secret is returned once, when
 * the token is created. The hash is kept out of {@code toString} all the same, and so is the user,
 * a lazy association that must not be loaded by a log line.
 */
@Entity
@Table(name = "personal_access_token")
@Data
@NoArgsConstructor
@EqualsAndHashCode
public class PersonalAccessToken {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private User user;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "token_hash", nullable = false, length = 64)
  @ToString.Exclude
  private String tokenHash;

  @Convert(converter = TokenScopeConverter.class)
  @Column(name = "scopes", nullable = false, length = 255)
  private EnumSet<TokenScope> scopes;

  @Column(name = "expiration_date", nullable = false)
  private Instant expirationDate;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
