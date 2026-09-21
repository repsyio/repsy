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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;
import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("RepoInfoResolver")
class RepoInfoResolverTest {

  @Mock private RepoTxService repoTxService;
  @Mock private MethodParameter parameter;

  @InjectMocks private RepoInfoResolver resolver;

  private static ServletWebRequest requestWith(final Map<String, String> uriVariables) {
    final var request = new ServletWebRequest(new MockHttpServletRequest());
    request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriVariables, SCOPE_REQUEST);
    return request;
  }

  @Test
  @DisplayName("resolves the repo named by the repoName URI variable")
  void resolvesTheRepoByName() {
    final var repoInfo = RepoInfo.builder().name("releases").build();
    when(this.repoTxService.getRepoByName("releases")).thenReturn(repoInfo);

    final var resolved =
        this.resolver.resolveArgument(
            this.parameter, null, requestWith(Map.of("repoName", "releases")), null);

    assertThat(resolved).isSameAs(repoInfo);
    verify(this.repoTxService).getRepoByName("releases");
  }

  @Test
  @DisplayName("answers repoNotFound when the route has no repoName variable")
  void refusesARouteWithoutARepoName() {
    final var webRequest = requestWith(Map.of("gemName", "rake"));

    assertThatThrownBy(() -> this.resolver.resolveArgument(this.parameter, null, webRequest, null))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("repoNotFound");
    verifyNoInteractions(this.repoTxService);
  }

  @Test
  @DisplayName("answers urlVariablesNotFound when the request carries no URI variables at all")
  void refusesARequestWithoutUriVariables() {
    final var webRequest = new ServletWebRequest(new MockHttpServletRequest());

    assertThatThrownBy(() -> this.resolver.resolveArgument(this.parameter, null, webRequest, null))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("urlVariablesNotFound");
    verifyNoInteractions(this.repoTxService);
  }
}
