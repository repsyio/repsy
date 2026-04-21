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
package io.repsy.os.server.protocols.shared.aop.resolvers;

import static org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;
import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.util.Map;
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
public class RepoInfoResolver implements HandlerMethodArgumentResolver {

  private final RepoTxService repoTxService;

  @Override
  public boolean supportsParameter(final MethodParameter parameter) {

    return RepoInfo.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public @Nullable Object resolveArgument(
      final MethodParameter parameter,
      final @Nullable ModelAndViewContainer mavContainer,
      final NativeWebRequest webRequest,
      final @Nullable WebDataBinderFactory binderFactory) {

    final var uriVariables = this.getUrlVariables(webRequest);

    final var repoName = ResolverUtils.extractRepoInfo(uriVariables);

    return this.repoTxService.getRepoByName(repoName);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> getUrlVariables(final NativeWebRequest webRequest) {

    final var uriVariables =
        (Map<String, String>)
            webRequest.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, SCOPE_REQUEST);

    if (uriVariables == null) {
      throw new ItemNotFoundException("urlVariablesNotFound");
    }

    return uriVariables;
  }
}
