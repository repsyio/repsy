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
package io.repsy.os.shared.user.repositories;

import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  @NonNull Optional<User> findByUsername(@NonNull String username);

  boolean existsByUsername(@NonNull String username);

  @NonNull List<User> findAllByRole(@NonNull UserRole role);

  @Query(
      """
  select u from User u
  where (:search is null or :search = ''
    or LOWER(u.username) like LOWER(CONCAT('%', :search, '%')))
  order by u.createdAt desc
  """)
  @NonNull Page<User> findAllWithSearch(
      @NonNull @Param("search") String search, @NonNull Pageable pageable);

  Long countByRole(UserRole userRole);

  /**
   * Locks every user row that holds {@code role} until the surrounding transaction ends and returns
   * their ids (RPS-1101). Every operation that can shrink the set of admins takes this lock before
   * it counts them, so two of them can never both see "two admins left" and both remove one.
   *
   * <p>Three details matter. It selects ids, not entities: a user that another transaction demoted
   * or deleted while this one waited is then never held in the persistence context as a stale copy.
   * The caller counts the list itself, because {@code FOR UPDATE} is not allowed on an aggregate in
   * PostgreSQL. And the {@code order by} gives every caller the same lock order, so two callers
   * queue up instead of deadlocking. Under READ COMMITTED a caller that waited re-tests the role
   * once the other transaction commits, so it sees the admins that are really left.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u.id from User u where u.role = :role order by u.id")
  @NonNull List<UUID> lockIdsByRole(@NonNull @Param("role") UserRole role);

  /**
   * Swaps a user's password hash only while it still holds {@code oldHash}, so a password change
   * that committed in the meantime is never overwritten by a re-hash of the old password. Written
   * as a bulk update because a full-row entity save would also write back every stale column.
   *
   * @return 1 if the hash was replaced, 0 if the user is gone or the hash had changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update User u set u.hash = :newHash where u.id = :id and u.hash = :oldHash")
  int replaceHash(
      @NonNull @Param("id") UUID id,
      @NonNull @Param("oldHash") String oldHash,
      @NonNull @Param("newHash") String newHash);

  /**
   * Records a login by writing the {@code last_login_at} column and nothing else. The login is
   * recorded on another thread, so a full-row entity save could write back a stale password, role,
   * username or token version over a change that committed after the row was read.
   *
   * @return 1 if the timestamp was written, 0 if no user has that username
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update User u set u.lastLoginAt = :at where u.username = :username")
  int updateLastLoginAt(
      @NonNull @Param("username") String username, @NonNull @Param("at") Instant at);
}
