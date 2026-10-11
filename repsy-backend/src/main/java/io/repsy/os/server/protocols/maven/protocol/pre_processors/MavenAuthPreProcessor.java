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
package io.repsy.os.server.protocols.maven.protocol.pre_processors;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.os.server.protocols.maven.shared.auth.services.MavenAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.os.server.shared.utils.UrlPropertiesUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Maven: Basic or Bearer, or the single-path download token of the web UI. */
@Component
public class MavenAuthPreProcessor extends BasicOrBearerAuthPreProcessor<MavenAuthenticator> {

  /**
   * The web UI downloads a file by navigating to it, which cannot set an {@code Authorization}
   * header, so it carries a short-lived download token for that one file in this parameter.
   */
  private static final String DOWNLOAD_TOKEN_PARAMETER = "downloadToken";

  public MavenAuthPreProcessor(
      final MavenAuthenticator authenticator, final MavenProtocolProvider provider) {
    super(authenticator, provider);
  }

  @Override
  protected boolean authenticateOtherwise(
      final ProtocolContext context,
      final HttpServletRequest request,
      final RepoInfo repoInfo,
      final Permission permission) {

    final var downloadToken = request.getParameter(DOWNLOAD_TOKEN_PARAMETER);

    if (downloadToken == null) {
      return false;
    }

    this.authenticator.handleDownloadToken(
        downloadToken,
        repoInfo.getStorageKey(),
        UrlPropertiesUtils.getRelativePath(context).getPath(),
        permission);

    return true;
  }
}
