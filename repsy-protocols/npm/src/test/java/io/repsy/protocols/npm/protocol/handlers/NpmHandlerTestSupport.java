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
package io.repsy.protocols.npm.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpServletRequest;

/** Builds what the npm handler tests need: a context the base parser hands out, and requests. */
final class NpmHandlerTestSupport {

  private NpmHandlerTestSupport() {}

  static BaseRepoInfo<UUID> repoInfo() {
    return BaseRepoInfo.<UUID>builder()
        .id(UUID.randomUUID())
        .storageKey(UUID.randomUUID())
        .name("npm")
        .build();
  }

  static ProtocolContext context(final String relativePath) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("npm")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo())
            .build());
    return context;
  }

  /** A request as the router sees it: the servlet path is the whole path below the context. */
  static MockHttpServletRequest request(final String method, final String servletPath) {
    final var request = new MockHttpServletRequest(method, servletPath);
    request.setServletPath(servletPath);
    return request;
  }

  /** A base parser that answers a fixed relative path and counts how often it is asked. */
  static final class FixedBaseParser implements PathParser {

    private final String relativePath;
    private final boolean known;
    private final AtomicInteger calls = new AtomicInteger();

    FixedBaseParser(final String relativePath, final boolean known) {
      this.relativePath = relativePath;
      this.known = known;
    }

    int calls() {
      return this.calls.get();
    }

    @Override
    public Optional<ProtocolContext> parse(final HttpServletRequest request) {
      this.calls.incrementAndGet();
      return this.known ? Optional.of(context(this.relativePath)) : Optional.empty();
    }
  }
}
