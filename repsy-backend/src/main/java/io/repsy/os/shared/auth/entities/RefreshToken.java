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
package io.repsy.os.shared.auth.entities;

import io.repsy.core.uuidv7.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@Entity
@Table(name = "refresh_tokens")
@RequiredArgsConstructor
public class RefreshToken {
  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
  private UUID userId;

  @Column(name = "family_id", columnDefinition = "uuid", nullable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;
}
