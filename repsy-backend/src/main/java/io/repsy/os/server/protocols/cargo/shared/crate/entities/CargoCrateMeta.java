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
package io.repsy.os.server.protocols.cargo.shared.crate.entities;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@Entity
@Table(name = "cargo_crate_meta")
@NoArgsConstructor
@ToString(exclude = "crate")
@EqualsAndHashCode(exclude = "crate")
public class CargoCrateMeta {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "crate_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private CargoCrate crate;

  @Column(name = "version", nullable = false, length = 64)
  private String version;

  @Column(name = "readme", columnDefinition = "text")
  private String readme;

  @Column(name = "license", length = 255)
  private String license;

  @Column(name = "license_file", length = 255)
  private String licenseFile;

  @Column(name = "documentation", length = 255)
  private String documentation;

  @Column(name = "edition", length = 10)
  private String edition;

  @Column(name = "rust_version", length = 20)
  private String rustVersion;

  @Column(name = "downloads", nullable = false)
  private long downloads;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
