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

import java.lang.reflect.Method;
import java.util.Arrays;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactoryCustomizer;

/**
 * Puts {@link StablePaging#withTieBreaker(Pageable)} in front of every Spring Data repository
 * method that takes a {@link Pageable}, in one place.
 *
 * <p>The sort a list endpoint pages by comes from the client (a creation time, a name, a version)
 * or from the query itself, and either can tie. Repositories are the single point every paged query
 * passes through, whatever builds its sort (a derived query, an explicit {@code order by}, a
 * projection), so the primary key is added there.
 */
@Configuration(proxyBeanMethods = false)
public class StablePagingConfiguration {

  /**
   * Registers the interceptor with every repository factory. Spring Data does not pick up {@link
   * RepositoryFactoryCustomizer} beans by itself, so a post-processor hands one to each {@link
   * RepositoryFactoryBeanSupport} before it builds its repository.
   */
  @Bean
  static BeanPostProcessor stablePagingRepositoryPostProcessor() {
    final RepositoryFactoryCustomizer customizer =
        repositoryFactory ->
            repositoryFactory.addRepositoryProxyPostProcessor(
                (factory, repositoryInformation) -> factory.addAdvice(new TieBreakerInterceptor()));

    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(final Object bean, final String beanName) {
        if (bean instanceof final RepositoryFactoryBeanSupport<?, ?, ?> factoryBean) {
          factoryBean.addRepositoryFactoryCustomizer(customizer);
        }

        return bean;
      }
    };
  }

  /** Replaces each {@link Pageable} argument of the intercepted call with a stable one. */
  static final class TieBreakerInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(final @NonNull MethodInvocation invocation) throws Throwable {
      final Method method = invocation.getMethod();

      if (Arrays.stream(method.getParameterTypes()).anyMatch(Pageable.class::isAssignableFrom)) {
        final var arguments = invocation.getArguments();

        for (var i = 0; i < arguments.length; i++) {
          if (arguments[i] instanceof final Pageable pageable) {
            arguments[i] = StablePaging.withTieBreaker(pageable);
          }
        }
      }

      return invocation.proceed();
    }
  }
}
