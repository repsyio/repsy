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
package io.repsy.os.server.protocols.helm.protocol.utils;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Qualifier("osHelmChartMuseumPathParser")
@RequiredArgsConstructor
@NullMarked
public class HelmChartMuseumPathParser implements PathParser {

  private static final Pattern PATTERN =
      Pattern.compile("^/api/(?<repoName>[a-zA-Z0-9_\\-]+)(?<suffix>/[^\\s#?&${}\\\\]*)?$");

  private final RepoTxService repoTxService;

  @Override
  public Optional<ProtocolContext> parse(final HttpServletRequest request) {
    final var path = request.getServletPath();
    final var matcher = PATTERN.matcher(path);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    final var repoName = matcher.group("repoName").toLowerCase(Locale.getDefault());
    final var rawSuffix = matcher.group("suffix");
    final var relativePath = rawSuffix != null ? "/api" + rawSuffix : "";

    return this.repoTxService
        .getRepoByNameAndType(repoName, RepoType.HELM)
        .flatMap(repoInfo -> this.createProtocolContext(repoInfo, repoName, relativePath));
  }

  private Optional<ProtocolContext> createProtocolContext(
      final RepoInfo repoInfo, final String repoName, final String relativePath) {

    final var context = new ProtocolContext();
    final var urlProperties =
        UrlParserProperties.builder()
            .repoName(repoName)
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();

    context.addProperty("urlProperties", urlProperties);
    return Optional.of(context);
  }
}
