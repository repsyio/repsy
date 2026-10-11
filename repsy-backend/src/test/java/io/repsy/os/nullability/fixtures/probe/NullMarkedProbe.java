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
package io.repsy.os.nullability.fixtures.probe;

import io.repsy.os.shared.repo.entities.Repo;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The two ways to declare a query that tolerates null, in a null-marked package. */
public final class NullMarkedProbe {

  private NullMarkedProbe() {}

  /** Repositories over {@link Repo}, the table that always has rows. */
  public interface Probe extends JpaRepository<Repo, UUID> {

    /** Null on no row, declared without {@code @Nullable}: the shape of the old {@code Long}. */
    @Query("select sum(r.diskUsage) from Repo r where r.id = :id")
    Long bareAggregate(UUID id);

    @Query("select sum(r.diskUsage) from Repo r where r.id = :id")
    @Nullable Long nullableAggregate(UUID id);

    @Query("select r.name from Repo r where (:name is null or r.name = :name)")
    List<String> bareParameter(String name);

    @Query("select r.name from Repo r where (:name is null or r.name = :name)")
    List<String> nullableParameter(@Nullable String name);
  }
}
