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
import static org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiFacadeResolver")
class ApiFacadeResolverTest {

  @Mock private MethodParameter parameter;
  @Mock private ProtocolApiFacade mavenFacade;
  @Mock private ProtocolApiFacade npmFacade;

  @Test
  @DisplayName("resolves the facade of the type of the repo the auth interceptor resolved")
  void resolvesTheFacadeOfTheRepoType() {
    final var resolver =
        new ApiFacadeResolver(
            Map.of(RepoType.MAVEN, this.mavenFacade, RepoType.NPM, this.npmFacade));
    final var request = new ServletWebRequest(new MockHttpServletRequest());
    request.setAttribute(
        ResolverUtils.REPO_INFO,
        RepoInfo.builder().name("registry").type(RepoType.NPM).build(),
        SCOPE_REQUEST);

    assertThat(resolver.resolveArgument(this.parameter, null, request, null))
        .isSameAs(this.npmFacade);
  }

  @Test
  @DisplayName(
      "answers repoNotFound, and ignores a repoType URI variable, when no repo was resolved")
  void refusesARouteWithoutAResolvedRepo() {
    final var resolver = new ApiFacadeResolver(Map.of(RepoType.MAVEN, this.mavenFacade));
    final var request = new ServletWebRequest(new MockHttpServletRequest());

    assertThatThrownBy(() -> resolver.resolveArgument(this.parameter, null, request, null))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("repoNotFound");
  }
}
