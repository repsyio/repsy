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
package io.repsy.protocols.ruby.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubyGemYankProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<RubyProtocolFacade> {

  private static final String YANK_PATH = "/api/v1/gems/yank";
  private static final String DEFAULT_PLATFORM = "ruby";

  protected AbstractRubyGemYankProtocolMethodHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {

    super(
        HandlerRoute.write(HttpMethod.DELETE).path(YANK_PATH::equals),
        basePathParser,
        facade,
        provider);
  }

  /**
   * Yank is a WRITE, like Cargo's yank and NuGet's unlist: it only unpublishes a version from the
   * index and keeps the {@code .gem} file (RPS-1238), so whoever may push may yank. A read-write
   * deploy token, which is what the panel's Ruby snippet has {@code gem yank} use, and a USER-role
   * account are therefore allowed; a read-only token is not (RPS-1317).
   */
  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    // gem yank sends form-encoded body; Spring Boot's FormContentFilter makes params available
    final var gemName = request.getParameter("gem_name");
    final var version = request.getParameter("version");
    final var platform = platformOrDefault(request.getParameter("platform"));

    if (gemName == null || gemName.isBlank() || version == null || version.isBlank()) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.TEXT_PLAIN)
          .body("gem_name and version are required");
    }

    this.facade.yankGem(context, gemName, version, platform);

    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .body("Successfully yanked gem: " + gemName + " (" + version + ")");
  }

  private static String platformOrDefault(final @Nullable String platform) {
    return (platform == null || platform.isBlank()) ? DEFAULT_PLATFORM : platform;
  }
}
