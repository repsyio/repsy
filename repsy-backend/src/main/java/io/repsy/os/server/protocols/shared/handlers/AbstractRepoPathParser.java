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
package io.repsy.os.server.protocols.shared.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.shared.utils.UrlPropertiesUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the repository a protocol request addresses from its servlet path (RPS-2062).
 *
 * <p>A subclass names its {@link RepoType} and two patterns. The normal pattern must expose a
 * {@code repoName} group and, unless {@link #relativePath(Matcher)} is overridden, an optional
 * {@code relativePath} group; the repository is looked up by that name (lower-cased) and type, and
 * a path of an unknown repository yields {@link Optional#empty()}. The optional registry-level
 * pattern matches the paths that belong to no repository (the Docker {@code /v2/}, Cargo {@code
 * /}); those get {@link #registryLevelContext(String)} without any lookup.
 */
public abstract class AbstractRepoPathParser implements PathParser {

  protected static final String REPO_NAME_GROUP = "repoName";
  protected static final String RELATIVE_PATH_GROUP = "relativePath";
  protected static final String REPO_NAME_REGEX = "(?<repoName>[a-zA-Z0-9_\\-]+)";

  /** Everything after the repository name. */
  protected static final String RELATIVE_PATH_REGEX = "(?<relativePath>/.*)?";

  /** What follows the repository name, without whitespace and URL metacharacters. */
  protected static final String STRICT_RELATIVE_PATH_REGEX = "(?<relativePath>/[^\\s#?&${}\\\\]*)?";

  private static final String URL_PROPERTIES = "urlProperties";

  private final RepoTxService repoTxService;
  private final RepoType repoType;
  private final @Nullable Pattern registryPattern;
  private final Pattern normalPattern;

  protected AbstractRepoPathParser(
      final RepoTxService repoTxService,
      final RepoType repoType,
      final @Nullable Pattern registryPattern,
      final Pattern normalPattern) {
    this.repoTxService = repoTxService;
    this.repoType = repoType;
    this.registryPattern = registryPattern;
    this.normalPattern = normalPattern;
  }

  /** {@code ^<prefix>/<repoName><relativePath>}, the relative path being anything. */
  protected static Pattern repoPattern(final String prefix) {
    return Pattern.compile("^" + prefix + "/" + REPO_NAME_REGEX + RELATIVE_PATH_REGEX);
  }

  /** {@code ^<prefix>/<repoName><relativePath>}, the relative path being {@code STRICT}. */
  protected static Pattern strictRepoPattern(final String prefix) {
    return Pattern.compile("^" + prefix + "/" + REPO_NAME_REGEX + STRICT_RELATIVE_PATH_REGEX);
  }

  @Override
  public Optional<ProtocolContext> parse(final HttpServletRequest request) {
    final var path = request.getServletPath();

    if (this.registryPattern != null && this.registryPattern.matcher(path).matches()) {
      return Optional.of(this.registryLevelContext(path));
    }

    final var matcher = this.normalPattern.matcher(path);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    final var repoName = matcher.group(REPO_NAME_GROUP).toLowerCase(Locale.getDefault());

    return this.repoTxService
        .findRepoByNameAndType(repoName, this.repoType)
        .map(repoInfo -> this.createContext(repoInfo, repoName, matcher));
  }

  /** The context of a path that addresses the registry, not a repository. */
  protected ProtocolContext registryLevelContext(final String path) {
    return UrlPropertiesUtils.createWithEmptyRepo("", new RelativePath(path));
  }

  /** The relative path of a matched repository path, by default its {@code relativePath} group. */
  protected RelativePath relativePath(final Matcher matcher) {
    return new RelativePath(Objects.toString(matcher.group(RELATIVE_PATH_GROUP), ""));
  }

  /** Lets a protocol put more than the {@code urlProperties} on the context of a repository. */
  protected void customize(final ProtocolContext context, final RepoInfo repoInfo) {
    // nothing by default
  }

  private ProtocolContext createContext(
      final RepoInfo repoInfo, final String repoName, final Matcher matcher) {
    final var context = new ProtocolContext();

    final var urlProperties =
        UrlParserProperties.builder()
            .repoName(repoName)
            .relativePath(this.relativePath(matcher))
            .repoInfo(repoInfo)
            .build();

    context.addProperty(URL_PROPERTIES, urlProperties);
    this.customize(context, repoInfo);

    return context;
  }
}
