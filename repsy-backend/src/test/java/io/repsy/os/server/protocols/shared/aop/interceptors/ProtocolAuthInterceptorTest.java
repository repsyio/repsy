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
package io.repsy.os.server.protocols.shared.aop.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

@DisplayName("ProtocolAuthInterceptor")
class ProtocolAuthInterceptorTest {

  /** A handler of a route that names no repo, which no {@code @RepoOperation} route may be. */
  static class Handlers {

    @RepoOperation
    public void withoutARepo() {
      // Only its annotation matters.
    }

    public void notARepoOperation() {
      // Only its missing annotation matters.
    }
  }

  private final RepoTxService repoTxService = mock(RepoTxService.class);
  private final ProtocolAuthService authService = mock(ProtocolAuthService.class);
  private final ProtocolAuthInterceptor interceptor =
      new ProtocolAuthInterceptor(Map.of(RepoType.MAVEN, this.authService), this.repoTxService);

  private static HandlerMethod handler(final String method) throws NoSuchMethodException {
    return new HandlerMethod(new Handlers(), Handlers.class.getMethod(method));
  }

  @Test
  @DisplayName("refuses a @RepoOperation route without a repoName variable, even with a repoType")
  void refusesARepoOperationWithoutARepoName() throws Exception {
    final var request = new MockHttpServletRequest();
    request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("repoType", "MAVEN"));

    assertThatThrownBy(
            () ->
                this.interceptor.preHandle(
                    request, new MockHttpServletResponse(), handler("withoutARepo")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{repoName}");
    verifyNoInteractions(this.repoTxService, this.authService);
  }

  @Test
  @DisplayName("lets a route that is not a @RepoOperation through untouched")
  void ignoresARouteThatIsNotARepoOperation() throws Exception {
    final var allowed =
        this.interceptor.preHandle(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            handler("notARepoOperation"));

    assertThat(allowed).isTrue();
    verifyNoInteractions(this.repoTxService, this.authService);
  }
}
