package io.repsy.os.server.protocols.shared.aop;

import io.repsy.os.server.protocols.shared.aop.interceptors.ProtocolAuthInterceptor;
import io.repsy.os.server.protocols.shared.aop.resolvers.ApiFacadeResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.AuthServiceResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoInfoResolver;
import io.repsy.os.server.protocols.shared.aop.resolvers.RepoPermissionInfoResolver;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@NullMarked
@Configuration
public class ProtocolEndpointDispatcher implements WebMvcConfigurer {

  private final RepoInfoResolver repoInfoResolver;
  private final AuthServiceResolver authServiceResolver;
  private final ApiFacadeResolver apiFacadeResolver;
  private final RepoPermissionInfoResolver permissionInfoResolver;
  private final ProtocolAuthInterceptor authInterceptor;

  public ProtocolEndpointDispatcher(
      @Lazy final RepoInfoResolver repoInfoResolver,
      @Lazy final AuthServiceResolver authServiceResolver,
      @Lazy final ApiFacadeResolver apiFacadeResolver,
      @Lazy final RepoPermissionInfoResolver permissionInfoResolver,
      @Lazy final ProtocolAuthInterceptor authInterceptor) {

    this.repoInfoResolver = repoInfoResolver;
    this.authServiceResolver = authServiceResolver;
    this.apiFacadeResolver = apiFacadeResolver;
    this.permissionInfoResolver = permissionInfoResolver;
    this.authInterceptor = authInterceptor;
  }

  @Override
  public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {

    resolvers.add(this.repoInfoResolver);
    resolvers.add(this.permissionInfoResolver);
    resolvers.add(this.authServiceResolver);
    resolvers.add(this.apiFacadeResolver);
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {

    registry
        .addInterceptor(this.authInterceptor)
        .addPathPatterns("/api/repos/**/")
        .addPathPatterns("/api/npm/packages/**/")
        .addPathPatterns("/api/pypi/packages/**/")
        .addPathPatterns("/api/mvn/artifacts/**/")
        .addPathPatterns("/api/mvn/key-stores/**/")
        .addPathPatterns("/api/cargo/crates/**/")
        .addPathPatterns("/api/go/modules/**/")
        .addPathPatterns("/api/docker/images/**/");
  }
}
