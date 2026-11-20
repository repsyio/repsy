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
package io.repsy.os.shared.connector.entities;

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
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The one-time code the consent page of a connected application hands back through the browser
 * (RPS-2660). It lives 60 seconds and is deleted when it is exchanged, successfully or not, for a
 * {@code CONNECTOR} token. Only the hash of the code is stored, and it is kept out of {@code
 * toString}, as is the lazy user.
 */
@Entity
@Table(name = "connector_authorization_code")
@Data
@NoArgsConstructor
@EqualsAndHashCode
public class ConnectorAuthorizationCode {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private User user;

  @Column(name = "code_hash", nullable = false, length = 64)
  @ToString.Exclude
  private String codeHash;

  @Column(name = "client_name", nullable = false, length = 150)
  private String clientName;

  @Convert(converter = TokenScopeConverter.class)
  @Column(name = "scopes", nullable = false, length = 255)
  private Set<TokenScope> scopes;

  @Column(name = "redirect_uri", nullable = false, length = 2048)
  private String redirectUri;

  @Column(name = "code_challenge", nullable = false, length = 64)
  private String codeChallenge;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
