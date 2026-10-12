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
package io.repsy.os.server.protocols.helm.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HelmHeaderPreProcessor (RPS-2212)")
class HelmHeaderPreProcessorTest {

  private final HelmHeaderPreProcessor preProcessor =
      new HelmHeaderPreProcessor(mock(HelmProtocolProvider.class), mock(RestResponseFactory.class));

  private static ProtocolContext publicRepoContext() {
    final var id = UUID.randomUUID();
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("repo")
            .relativePath(new RelativePath("/file"))
            .repoInfo(
                RepoInfo.builder().id(id).storageKey(id).name("repo").privateRepo(false).build())
            .build());
    return context;
  }

  private ProcessorResult process(final Map<String, Object> properties) {
    return this.preProcessor.process(
        publicRepoContext(),
        mock(HttpServletRequest.class),
        mock(HttpServletResponse.class),
        properties);
  }

  @Test
  @DisplayName("a route without a permission is refused on a public repo, even with the skip flag")
  void undeclaredPermissionIsRefused() {
    assertThatThrownBy(() -> this.process(Map.of())).isInstanceOf(AccessNotAllowedException.class);
    assertThatThrownBy(
            () -> this.process(Map.of(HandlerPropertyKeys.SKIP_HEADER_PRE_PROCESSOR, true)))
        .isInstanceOf(AccessNotAllowedException.class);
  }

  @Test
  @DisplayName("a declared read of a public repo passes")
  void declaredReadPasses() {
    assertThat(this.process(Map.of(HandlerPropertyKeys.PERMISSION, Permission.READ)).isEmpty())
        .isTrue();
  }
}
