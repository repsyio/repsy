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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities;

import io.repsy.core.uuidv7.UuidV7;
import io.repsy.protocols.ruby.shared.utils.GemspecParser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@Entity
@Table(name = "ruby_gem_dependency")
@NoArgsConstructor
@ToString(exclude = "gemVersion")
public class RubyGemDependency {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "gem_version_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RubyGemVersion gemVersion;

  @Column(name = "name", nullable = false, length = GemspecParser.MAX_DEPENDENCY_NAME_LENGTH)
  private String name;

  @Column(
      name = "requirements",
      nullable = false,
      length = GemspecParser.MAX_DEPENDENCY_REQUIREMENTS_LENGTH)
  private String requirements;

  @Column(name = "type", nullable = false, length = 16)
  private String type;

  /**
   * Identifier-based equality: two ruby gem dependencys are equal when they are the same instance
   * or carry the same non-null id. One that has not been persisted yet has no id and equals only
   * itself. {@code getId()} is used on both sides so a Hibernate proxy is compared by its real id.
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof final RubyGemDependency other)) {
      return false;
    }

    return this.getId() != null && Objects.equals(this.getId(), other.getId());
  }

  /**
   * Constant on purpose: the id is assigned on persist and the other columns can change on flush,
   * so a hash derived from them would move a ruby gem dependency held in a {@code HashSet} into the
   * wrong bucket. It also keeps the lazy associations out of the hash.
   */
  @Override
  public int hashCode() {
    return RubyGemDependency.class.hashCode();
  }
}
