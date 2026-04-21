package io.repsy.os.server.protocols.shared.aop.resolvers;

import io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@NullMarked
@RequiredArgsConstructor
public class RepoPermissionInfoResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(final MethodParameter parameter) {

    return RepoPermissionInfo.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public @Nullable Object resolveArgument(
      final MethodParameter parameter,
      final @Nullable ModelAndViewContainer mavContainer,
      final NativeWebRequest webRequest,
      final @Nullable WebDataBinderFactory binderFactory) {

    return ResolverUtils.extractRepoPermissionInfo(webRequest);
  }
}
