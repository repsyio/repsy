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
package io.repsy.os.server.protocols.npm.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component("osNpmPathParser")
@RequiredArgsConstructor
@NullMarked
public class NpmPathParser implements PathParser {
  private static final String REPO_NAME = "repoName";
  private static final String RELATIVE_PATH = "relativePath";
  private static final String REPO_NAME_REGEX = "(?<repoName>[a-zA-Z0-9_\\-]+)";
  private static final String RELATIVE_PATH_REGEX = "(?<relativePath>/.*)?";

  private static final Pattern PATTERN =
      Pattern.compile("^/" + REPO_NAME_REGEX + RELATIVE_PATH_REGEX);

  private final RepoTxService repoTxService;

  @Override
  public Optional<ProtocolContext> parse(final HttpServletRequest request) {
    final var path = request.getServletPath();
    final var matcher = PATTERN.matcher(path);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    final var repoName = matcher.group(REPO_NAME).toLowerCase(Locale.getDefault());

    final var repoInfoOpt = this.repoTxService.getRepoByNameAndType(repoName, RepoType.NPM);

    return repoInfoOpt.flatMap(repoInfo -> this.createProtocolContext(repoInfo, repoName, matcher));
  }

  private Optional<ProtocolContext> createProtocolContext(
      final RepoInfo repoInfo, final String repoName, final Matcher matcher) {

    final var context = new ProtocolContext();

    final var urlProperties =
        UrlParserProperties.builder()
            .repoName(repoName)
            .relativePath(new RelativePath(Objects.toString(matcher.group(RELATIVE_PATH), "")))
            .repoInfo(repoInfo)
            .build();

    context.addProperty("urlProperties", urlProperties);

    return Optional.of(context);
  }
}
