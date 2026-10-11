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
package io.repsy.os.nullability.fixtures;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Planted violations of the rules in {@code RepositoryNullabilityRules}: each method must be found
 * by the rule it is named after, and the {@code Ok} methods by none of them (RPS-2077).
 */
public final class NullabilityViolations {

  private NullabilityViolations() {}

  /** The entity the fixture repositories are declared for. */
  public static class Row {}

  /** Methods that break a rule. */
  public interface BadRepository extends JpaRepository<Row, UUID> {

    @Query("select r from Row r where (:name is null or r.name = :name)")
    Collection<Row> isNullParameter(String name);

    @Query("select r from Row r where r.name = coalesce(:name, r.name)")
    Collection<Row> coalesceParameter(@Param("name") String other);

    @Query(value = "select * from row where name = cast(:name as varchar)", nativeQuery = true)
    Collection<Row> castParameter(String name);

    @Query("select r from Row r where (?1 is null or r.name = ?1)")
    Collection<Row> positionalParameter(String name);

    @Query("select sum(r.size) from Row r")
    Long nullableAggregate();

    Row findByName(String name);

    Row bareEntityFromQuery(UUID id);
  }

  /** Methods that follow every rule. */
  public interface OkRepository extends JpaRepository<Row, UUID> {

    @Query("select r from Row r where (:name is null or r.name = :name)")
    Collection<Row> isNullParameter(@Nullable String name);

    @Query("select r from Row r where (:name is null or r.name = :name)")
    Collection<Row> annotatedByParam(@Param("name") @Nullable String name);

    @Query("select r from Row r where r.name in :names and (:name is null or r.name = :name)")
    Collection<Row> nullableTypeUse(Collection<String> names, @Nullable String name);

    @Query("select sum(r.size) from Row r")
    @Nullable Long nullableAggregate();

    @Query("select coalesce(sum(r.size), 0) from Row r")
    long coalescedAggregate();

    Optional<Row> findByName(String name);

    @Nullable Row findNullableByName(String name);

    Long countByName(String name);
  }
}
