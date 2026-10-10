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

import io.repsy.os.server.protocols.shared.aop.interceptors.ProtocolAuthInterceptor;
import io.repsy.os.server.protocols.shared.aop.resolvers.ApiFacadeResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.AuthServiceResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoInfoResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoPermissionInfoResolver;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@NullMarked
@Configuration
public class ProtocolEndpointDispatcher implements WebMvcConfigurer {

  private final ObjectProvider<RepoInfoResolver> repoInfoResolver;
  private final ObjectProvider<AuthServiceResolver> authServiceResolver;
  private final ObjectProvider<ApiFacadeResolver> apiFacadeResolver;
  private final ObjectProvider<RepoPermissionInfoResolver> permissionInfoResolver;
  private final ObjectProvider<ProtocolAuthInterceptor> authInterceptor;

  // The providers are resolved in addArgumentResolvers and addInterceptors, not here. The MVC
  // infrastructure injects every WebMvcConfigurer while it creates mvcConversionService, and the
  // resolvers lead to services (PypiPackageService and others) that inject that same
  // ConversionService, so constructor injection is a bean cycle. Both callbacks run later, when
  // the request mapping beans are built from the finished conversion service.
  public ProtocolEndpointDispatcher(
      final ObjectProvider<RepoInfoResolver> repoInfoResolver,
      final ObjectProvider<AuthServiceResolver> authServiceResolver,
      final ObjectProvider<ApiFacadeResolver> apiFacadeResolver,
      final ObjectProvider<RepoPermissionInfoResolver> permissionInfoResolver,
      final ObjectProvider<ProtocolAuthInterceptor> authInterceptor) {

    this.repoInfoResolver = repoInfoResolver;
    this.authServiceResolver = authServiceResolver;
    this.apiFacadeResolver = apiFacadeResolver;
    this.permissionInfoResolver = permissionInfoResolver;
    this.authInterceptor = authInterceptor;
  }

  @Override
  public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {

    resolvers.add(this.repoInfoResolver.getObject());
    resolvers.add(this.permissionInfoResolver.getObject());
    resolvers.add(this.authServiceResolver.getObject());
    resolvers.add(this.apiFacadeResolver.getObject());
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {

    // Every panel route, not a list of the families that have one: the interceptor does nothing
    // for a handler without @RepoOperation, and a list forgot /api/mvn/groups/** (RPS-1558), which
    // left a @RepoOperation route without its authorization. RepoOperationRoutesStatusIT keeps
    // every @RepoOperation route under /api/.
    registry.addInterceptor(this.authInterceptor.getObject()).addPathPatterns("/api/**");
  }
}
