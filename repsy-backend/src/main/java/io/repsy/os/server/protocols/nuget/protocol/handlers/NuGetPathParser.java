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
package io.repsy.os.server.protocols.nuget.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.shared.handlers.AbstractRepoPathParser;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component("osNuGetPathParser")
public class NuGetPathParser extends AbstractRepoPathParser {

  /** {@code v3} is the service index, so it is never a repository name. */
  private static final Pattern NORMAL_PATTERN =
      Pattern.compile("^/(?<repoName>(?!v3(/|$))[a-zA-Z0-9_\\-]+)" + RELATIVE_PATH_REGEX);

  private static final Pattern REGISTRY_LEVEL_PATTERN = Pattern.compile("^/v3/index\\.json$");

  public NuGetPathParser(final RepoTxService repoTxService) {
    super(repoTxService, RepoType.NUGET, REGISTRY_LEVEL_PATTERN, NORMAL_PATTERN);
  }

  /** The service index carries only its path, no {@code urlProperties}. */
  @Override
  protected ProtocolContext registryLevelContext(final String path) {
    final var context = new ProtocolContext();
    context.addProperty("relativePath", new RelativePath(path));
    return context;
  }

  @Override
  protected void customize(final ProtocolContext context, final RepoInfo repoInfo) {
    context.addProperty("repoInfo", repoInfo);
  }
}
