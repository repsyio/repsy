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
package io.repsy.os.server.protocols.docker.shared.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract for the identifier-based {@code equals}/{@code hashCode} of the Docker JPA entities: the
 * id decides equality, the mutable state never does, and a hash-based collection stays consistent
 * when the id is assigned or the state changes after an entity was added.
 */
public abstract class AbstractEntityIdentityTest<T> {

  /** Creates an entity carrying {@code id} (possibly {@code null}) and some state. */
  protected abstract T newEntity(UUID id);

  /** Changes the mutable state of the entity, as a flush of a version or timestamp would. */
  protected abstract void changeState(T entity);

  /** Creates an instance that reports {@code id} only through {@code getId()}, like a proxy. */
  protected abstract T newProxy(UUID id);

  /** Assigns the id to an entity created without one, as persisting it would. */
  protected abstract void assignId(T entity, UUID id);

  /**
   * A lazy collection stand-in that fails on any use that would initialise it, so a test proves an
   * entity's {@code hashCode} and {@code toString} leave the association alone.
   */
  protected static final class UntouchableSet<E> extends HashSet<E> {

    private static final long serialVersionUID = 1L;

    public UntouchableSet() {
      super();
    }

    @Override
    public int hashCode() {
      throw new IllegalStateException("lazy collection was hashed");
    }

    @Override
    public String toString() {
      throw new IllegalStateException("lazy collection was printed");
    }

    @Override
    public Iterator<E> iterator() {
      throw new IllegalStateException("lazy collection was iterated");
    }
  }

  private static UUID copyOf(final UUID uuid) {
    return new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  @Test
  @DisplayName("Entities with the same id on distinct UUID instances are equal and hash alike")
  void sameIdOnDistinctUuidInstancesIsEqual() {
    final var uuid = UUID.randomUUID();
    final var first = this.newEntity(uuid);
    final var second = this.newEntity(copyOf(uuid));

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("Entities with different ids are not equal, even with the same state")
  void differentIdsAreNotEqual() {
    assertThat(this.newEntity(UUID.randomUUID())).isNotEqualTo(this.newEntity(UUID.randomUUID()));
  }

  @Test
  @DisplayName("Equality follows the id, not the mutable state")
  void equalityIgnoresState() {
    final var id = UUID.randomUUID();
    final var first = this.newEntity(id);
    final var second = this.newEntity(id);

    this.changeState(second);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("Entities without an id equal only themselves")
  void transientEntitiesEqualOnlyThemselves() {
    final var entity = this.newEntity(null);

    assertThat(entity).isEqualTo(entity);
    assertThat(entity).isNotEqualTo(this.newEntity(null));
  }

  @Test
  @DisplayName("An entity is never equal to null or another type")
  void notEqualToNullOrOtherType() {
    final var entity = this.newEntity(UUID.randomUUID());

    assertThat(entity).isNotEqualTo(null).isNotEqualTo("other type");
  }

  @Test
  @DisplayName("A proxy is compared through getId() on both sides")
  void proxyIsComparedByItsId() {
    final var id = UUID.randomUUID();
    final var entity = this.newEntity(id);
    final var proxy = this.newProxy(copyOf(id));

    assertThat(entity).isEqualTo(proxy);
    assertThat(proxy).isEqualTo(entity).hasSameHashCodeAs(entity);
    assertThat(entity).isNotEqualTo(this.newProxy(UUID.randomUUID()));
  }

  @Test
  @DisplayName("A transient entity stays findable in a HashSet after its id is assigned")
  void transientEntityStaysFindableAfterIdAssigned() {
    final var entity = this.newEntity(null);
    final var entities = new HashSet<T>();
    entities.add(entity);
    final var hashBefore = entity.hashCode();

    this.assignId(entity, UUID.randomUUID());

    assertThat(entity).hasSameHashCodeAs(hashBefore);
    assertThat(entities).contains(entity);
    assertThat(entities.remove(entity)).isTrue();
  }

  @Test
  @DisplayName("A member of a HashSet stays findable after its state changes")
  void memberStaysFindableAfterStateChange() {
    final var entity = this.newEntity(UUID.randomUUID());
    final var entities = new HashSet<T>();
    entities.add(entity);

    this.changeState(entity);

    assertThat(entities).contains(entity);
    assertThat(entities.remove(entity)).isTrue();
  }

  @Test
  @DisplayName("A HashSet de-duplicates entities loaded as separate instances of the same row")
  void hashSetDeduplicatesSameRow() {
    final var id = UUID.randomUUID();
    final var entities = new HashSet<T>();

    entities.add(this.newEntity(id));
    entities.add(this.newEntity(copyOf(id)));
    entities.add(this.newEntity(UUID.randomUUID()));

    assertThat(entities).hasSize(2);
  }
}
