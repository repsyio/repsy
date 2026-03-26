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
package io.repsy.os.server.protocols.docker.protocol.utils;

import com.google.common.base.Splitter;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class DockerScopeParserComponent implements DockerScopeParser<UUID> {

  private static final int MIN_SCOPE_ARGS = 2;

  private final RepoTxService repoTxService;

  public RepoInfo getRepoInfo(final String repoName) {

    return this.repoTxService.getRepo(repoName, RepoType.DOCKER);
  }

  public Optional<RepoInfo> getRepoInfoByScope(final String scope) {

    for (final var repo : Splitter.on(' ').split(scope)) {
      final var repoInfo = this.extractRepoInfo(repo);

      if (repoInfo.isPresent()) {
        return repoInfo;
      }
    }

    return Optional.empty();
  }

  private Optional<RepoInfo> extractRepoInfo(final String repo) {

    final var parts = this.parseRepo(repo);

    if (parts.length < MIN_SCOPE_ARGS || "*".equals(parts[0])) {
      return Optional.empty();
    }

    return Optional.of(this.getRepoInfo(parts[0]));
  }

  private String[] parseRepo(final String repo) {

    final var firstColon = repo.indexOf(':');
    final var lastColon = repo.lastIndexOf(':');

    if (firstColon == -1 || firstColon == lastColon) {
      return new String[0];
    }

    final var path = repo.substring(firstColon + 1, lastColon);

    return path.split("/");
  }
}
