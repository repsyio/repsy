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
package io.repsy.os.server.protocols.helm.shared.chart.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.os.shared.repo.entities.Repo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@Entity
@Table(
    name = "helm_chart",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ux_helm_chart__repo_id_name",
            columnNames = {"repo_id", "name"}))
@NoArgsConstructor
public class HelmChart {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version_lock", nullable = false)
  private Integer versionLock = 0;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "repo_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Repo repo;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  @CreationTimestamp
  private Instant createdAt;
}
