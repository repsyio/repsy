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
@EqualsAndHashCode(exclude = "goModule")
public class GoModuleVersion {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "module_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private GoModule goModule;

  @Column(name = "version", nullable = false)
  private String version;

  /** The Go toolchain version declared in go.mod (e.g. "1.21"). Null until .mod is uploaded. */
  @Column(name = "go_version")
  private @Nullable String goVersion;

  /** h1: hash of the uploaded go.mod file. Null until .mod is uploaded. */
  @Column(name = "mod_hash", length = 100)
  private @Nullable String modHash;

  /** h1: hash of the uploaded zip file. Null until .zip is uploaded. */
  @Column(name = "zip_hash", length = 100)
  private @Nullable String zipHash;

  @Column(name = "deleted", nullable = false)
  private boolean deleted = false;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
