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

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.ResourceResponses;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public abstract class AbstractNpmPackageDownloadProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/(.+?)/-/(.+)");

  public AbstractNpmPackageDownloadProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET)
            .path(path -> DOWNLOAD_PATTERN.matcher(path).matches() && !path.contains("dist-tags")),
        basePathParser,
        npmProtocolFacade,
        provider);
  }

  /**
   * The file name without the scope that a client may put before it ({@code @scope/name-1.tgz}).
   */
  private static String bareFilename(final String filename) {

    return filename.substring(filename.lastIndexOf('/') + 1);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext protocolContext,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(protocolContext).getPath();
    final var matcher = DOWNLOAD_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var packagePath = matcher.group(1);
    final var filename = matcher.group(2);

    try {
      final var pathVars = ExtractPath.extractPathVars(packagePath);
      final var resource =
          this.facade.getTarball(
              protocolContext, pathVars.scopeName(), pathVars.packageName(), filename);

      // Without a header of its own Spring names the download "f.txt" and shows it inline (an
      // answer to the reflected-file-download attack, which never applies to a tarball): a browser
      // or a download tool would save the tarball under that name (RPS-1363).
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header(
              HttpHeaders.CONTENT_DISPOSITION, ResourceResponses.attachment(bareFilename(filename)))
          .body(resource);

    } catch (final UnAuthorizedException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .build();
    }
  }
}
