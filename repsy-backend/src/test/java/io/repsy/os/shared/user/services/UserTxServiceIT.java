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
package io.repsy.os.shared.user.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link UserTxService} against a containerized PostgreSQL database
 * (Flyway-migrated).
 *
 * <p>Every test method runs in the transaction inherited from {@link AbstractIntegrationTest} and
 * is rolled back afterwards, which is what makes these tests meaningful for generated values: the
 * returned DTO must already carry them without the test flushing the persistence context first.
 */
@DisplayName("UserTxService")
class UserTxServiceIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("create() returns the persisted createdAt")
  void createReturnsPersistedCreatedAt() {
    // users.username is varchar(25), so keep the unique suffix short.
    final var username = "txs-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final var hash = PasswordHasher.hash("Password1!");

    // Deliberately no flush before reading the result: the value has to be there on return.
    final var userInfo = this.userTxService.create(username, UserRole.USER, hash);

    assertThat(userInfo.getCreatedAt()).isNotNull();

    // Flush and drop the first-level cache so the entity below is re-read from the database.
    this.entityManager.flush();
    this.entityManager.clear();
    final var persisted = this.userRepository.findById(userInfo.getId()).orElseThrow();

    assertThat(persisted.getCreatedAt()).isNotNull();
    assertThat(userInfo.getCreatedAt()).isEqualTo(persisted.getCreatedAt());
  }
}
