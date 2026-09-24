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
package io.repsy.os.server.protocols.cargo.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoKeyword;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface CargoKeywordRepository extends JpaRepository<CargoKeyword, UUID> {

  Optional<CargoKeyword> findByKeyword(String keyword);

  /**
   * Inserts the keyword unless it already exists. It does not raise the unique-index violation,
   * which would abort the caller's PostgreSQL transaction: that transaction also holds the version
   * row while the crate file is written (RPS-1124). When a concurrent publish has inserted the same
   * keyword but not committed yet, the statement waits for it and then skips the insert, so the
   * caller reads the row back with {@link #findByKeyword}.
   *
   * @return 1 when the row was inserted, 0 when it already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."cargo_keyword" ("id", "keyword")
            values (:id, :keyword)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, String keyword);
}
