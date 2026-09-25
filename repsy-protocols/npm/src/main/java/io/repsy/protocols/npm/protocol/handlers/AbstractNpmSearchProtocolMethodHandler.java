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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import io.repsy.protocols.npm.shared.search.NpmSearchService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /{repo}/-/v1/search?text=&size=&from=}, which {@code npm search} calls. It searches
 * the packages of the repository in the URL only, and needs read access to it.
 */
@NullMarked
public abstract class AbstractNpmSearchProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final NpmSearchService<ID> searchService;

  public AbstractNpmSearchProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmSearchService<ID> searchService,
      final NpmProtocolProvider provider) {
    this.pathParser = new NpmExactPathParser(basePathParser, HttpMethod.GET, "/-/v1/search");
    this.searchService = searchService;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "writeOperation", false,
        "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return this.pathParser;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var query =
        NpmSearchQuery.parse(
            request.getParameter("text"),
            request.getParameter("size"),
            request.getParameter("from"));

    final var result =
        this.searchService.search(ProtocolContextUtils.<ID>getRepoInfo(context), query);

    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
  }
}
