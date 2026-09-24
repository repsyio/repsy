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
package io.repsy.os.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("StablePagingConfiguration")
class StablePagingConfigurationTest {

  /** A repository shape: one method that pages, one that does not. */
  @SuppressWarnings("unused")
  interface Repository {

    Page<String> findAll(UUID repoId, Pageable pageable);

    List<String> findAll(UUID repoId);
  }

  private final StablePagingConfiguration.TieBreakerInterceptor interceptor =
      new StablePagingConfiguration.TieBreakerInterceptor();

  private static MethodInvocation invocation(final Method method, final Object[] arguments)
      throws Throwable {

    final var invocation = Mockito.mock(MethodInvocation.class);
    Mockito.when(invocation.getMethod()).thenReturn(method);
    Mockito.when(invocation.getArguments()).thenReturn(arguments);
    Mockito.when(invocation.proceed()).thenReturn("result");

    return invocation;
  }

  @Test
  @DisplayName("hands the query a pageable that ends its sort on the id")
  void replacesPageable() throws Throwable {
    final var method = Repository.class.getMethod("findAll", UUID.class, Pageable.class);
    final var arguments =
        new Object[] {UUID.randomUUID(), PageRequest.of(2, 5, Sort.by("createdAt"))};

    final var result = this.interceptor.invoke(invocation(method, arguments));

    assertThat(result).isEqualTo("result");
    assertThat(((Pageable) arguments[1]).getSort())
        .containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    assertThat(((Pageable) arguments[1]).getPageNumber()).isEqualTo(2);
  }

  @Test
  @DisplayName("leaves a method without a pageable alone")
  void leavesOtherMethodsAlone() throws Throwable {
    final var method = Repository.class.getMethod("findAll", UUID.class);
    final var repoId = UUID.randomUUID();
    final var arguments = new Object[] {repoId};

    this.interceptor.invoke(invocation(method, arguments));

    assertThat(arguments).containsExactly(repoId);
  }
}
