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
package io.repsy.os.nullability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.os.AbstractIT;
import io.repsy.os.nullability.fixtures.probe.NullMarkedProbe.Probe;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves what Spring Data does with null under a package-level {@code @NullMarked} (RPS-2077), the
 * reason the repository methods say where null is legal: a null argument for a parameter that is
 * not {@code @Nullable} is refused before the query runs, and a null result of a method that is not
 * {@code @Nullable} is an {@code EmptyResultDataAccessException}. The annotated twin of each method
 * keeps the behaviour the query always had.
 *
 * <p>The failing cases are the flip-and-fail of the fixes: {@code RepoRepository#getTotalDiskUsage}
 * had the shape of {@code bareAggregate}, and the repositories with an {@code (:x is null or ...)}
 * filter had the shape of {@code bareParameter} before they were annotated.
 */
@Transactional
class NullMarkedRepositoryIT extends AbstractIT {

  @Autowired private EntityManager entityManager;
  @Autowired private RepoRepository repoRepository;

  private Probe probe;

  @BeforeEach
  void probe() {
    this.probe = new JpaRepositoryFactory(this.entityManager).getRepository(Probe.class);
  }

  @Test
  void aBareAggregateThatIsNullIsRefused() {
    assertThatThrownBy(() -> this.probe.bareAggregate(UUID.randomUUID()))
        .isInstanceOf(EmptyResultDataAccessException.class);
  }

  @Test
  void aNullableAggregateThatIsNullIsReturned() {
    assertThat(this.probe.nullableAggregate(UUID.randomUUID())).isNull();
  }

  @Test
  void aNullArgumentForAParameterThatIsNotNullableIsRefused() {
    assertThatThrownBy(() -> this.probe.bareParameter(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullArgumentForANullableParameterIsTheUnfilteredQuery() {
    assertThat(this.probe.nullableParameter(null)).isNotEmpty();
  }

  @Test
  void theTotalDiskUsageOfTheRepositoryIsDeclaredNullable() throws NoSuchMethodException {
    final var method = RepoRepository.class.getMethod("getTotalDiskUsage");

    assertThat(method.getAnnotatedReturnType().getAnnotations())
        .extracting(annotation -> annotation.annotationType().getSimpleName())
        .contains("Nullable");
    assertThat(this.repoRepository.getTotalDiskUsage()).isNotNull();
  }
}
