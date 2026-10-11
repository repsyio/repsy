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

import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.shared.handlers.AbstractRepoPathParser;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** The ChartMuseum API, {@code /api/<repo>/...}; its relative path keeps the {@code /api}. */
@Component
@Qualifier("osHelmChartMuseumPathParser")
public class HelmChartMuseumPathParser extends AbstractRepoPathParser {

  private static final String SUFFIX_GROUP = "suffix";

  private static final Pattern PATTERN =
      Pattern.compile("^/api/" + REPO_NAME_REGEX + "(?<suffix>/[^\\s#?&${}\\\\]*)?$");

  public HelmChartMuseumPathParser(final RepoTxService repoTxService) {
    super(repoTxService, RepoType.HELM, null, PATTERN);
  }

  @Override
  protected RelativePath relativePath(final Matcher matcher) {
    final var suffix = matcher.group(SUFFIX_GROUP);

    return new RelativePath(suffix != null ? "/api" + suffix : "");
  }
}
