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
package io.repsy.os.server.protocols.shared.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.shared.aop.interceptors.ProtocolAuthInterceptor;
import io.repsy.os.server.protocols.shared.aop.resolvers.ApiFacadeResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.AuthServiceResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoInfoResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoPermissionInfoResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProtocolEndpointDispatcher")
class ProtocolEndpointDispatcherTest {

  @Mock private RepoInfoResolver repoInfoResolver;
  @Mock private AuthServiceResolver authServiceResolver;
  @Mock private ApiFacadeResolver apiFacadeResolver;
  @Mock private RepoPermissionInfoResolver permissionInfoResolver;
  @Mock private ProtocolAuthInterceptor authInterceptor;

  @Mock private ObjectProvider<RepoInfoResolver> repoInfoProvider;
  @Mock private ObjectProvider<AuthServiceResolver> authServiceProvider;
  @Mock private ObjectProvider<ApiFacadeResolver> apiFacadeProvider;
  @Mock private ObjectProvider<RepoPermissionInfoResolver> permissionInfoProvider;
  @Mock private ObjectProvider<ProtocolAuthInterceptor> authInterceptorProvider;

  private void provide() {
    when(this.repoInfoProvider.getObject()).thenReturn(this.repoInfoResolver);
    when(this.authServiceProvider.getObject()).thenReturn(this.authServiceResolver);
    when(this.apiFacadeProvider.getObject()).thenReturn(this.apiFacadeResolver);
    when(this.permissionInfoProvider.getObject()).thenReturn(this.permissionInfoResolver);
  }

  private ProtocolEndpointDispatcher dispatcher() {
    return new ProtocolEndpointDispatcher(
        this.repoInfoProvider,
        this.authServiceProvider,
        this.apiFacadeProvider,
        this.permissionInfoProvider,
        this.authInterceptorProvider);
  }

  @Test
  @DisplayName("registers the four argument resolvers in order")
  void registersTheArgumentResolversInOrder() {
    this.provide();
    final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

    dispatcher().addArgumentResolvers(resolvers);

    assertThat(resolvers)
        .containsExactly(
            this.repoInfoResolver,
            this.permissionInfoResolver,
            this.authServiceResolver,
            this.apiFacadeResolver);
  }

  @Test
  @DisplayName("maps the auth interceptor onto every panel route")
  void mapsTheAuthInterceptorOntoEveryPanelRoute() {
    when(this.authInterceptorProvider.getObject()).thenReturn(this.authInterceptor);
    final var registry = new ExposedInterceptorRegistry();

    dispatcher().addInterceptors(registry);

    final var interceptors = registry.registered();
    assertThat(interceptors).hasSize(1);

    final var mapped = (MappedInterceptor) interceptors.getFirst();
    assertThat(mapped.getInterceptor()).isSameAs(this.authInterceptor);
    assertThat(mapped.getIncludePathPatterns()).containsExactly("/api/**");
  }

  @Test
  @DisplayName("resolves nothing at construction, so the MVC conversion service is not in a cycle")
  void resolvesNothingAtConstruction() {
    dispatcher();

    verifyNoInteractions(
        this.repoInfoProvider,
        this.authServiceProvider,
        this.apiFacadeProvider,
        this.permissionInfoProvider,
        this.authInterceptorProvider);
  }

  private static final class ExposedInterceptorRegistry extends InterceptorRegistry {

    List<Object> registered() {
      return getInterceptors();
    }
  }
}
