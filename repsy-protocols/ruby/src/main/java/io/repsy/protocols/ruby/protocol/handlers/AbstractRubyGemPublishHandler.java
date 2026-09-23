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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@NullMarked
public abstract class AbstractRubyGemPublishHandler implements ProtocolMethodHandler {

  private static final String PUBLISH_PATH = "/api/v1/gems";
  private static final String GEM_NAME = "gemName";
  private static final String GEM_VERSION = "gemVersion";

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;
  private final long maxGemBytes;

  /**
   * @param maxGemBytes The largest gem a push may carry. The body is a raw request body, which no
   *     multipart limit applies to, so a larger one is refused with 413 instead of being read.
   */
  protected AbstractRubyGemPublishHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider,
      final long maxGemBytes) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    this.maxGemBytes = maxGemBytes;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.POST.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      final var parsedOpt = this.basePathParser.parse(request);
      if (parsedOpt.isEmpty()) {
        return Optional.empty();
      }
      final var relativePath = ProtocolContextUtils.getRelativePath(parsedOpt.get()).getPath();
      return PUBLISH_PATH.equals(relativePath) ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    // A client that declares an oversized body is refused before any of it is read.
    if (request.getContentLengthLong() > this.maxGemBytes) {
      throw new MaxUploadSizeExceededException(this.maxGemBytes);
    }

    // The gem is spooled to a temporary file, hashing it on the way, instead of being held in
    // memory: the metadata is read from the file and then the file is streamed into storage.
    try (final var gem = SpooledUpload.spool(request.getInputStream(), this.maxGemBytes)) {
      this.facade.publishGem(context, gem);
      final var gemName = (String) context.getProperty(GEM_NAME);
      final var gemVersion = (String) context.getProperty(GEM_VERSION);
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_PLAIN)
          .body("Successfully registered gem: " + gemName + " (" + gemVersion + ")");
    } catch (final EntryTooLargeException e) {
      // The body was chunked or understated its length, and outgrew the limit while it was read.
      throw new MaxUploadSizeExceededException(this.maxGemBytes, e);
    }
    // A malformed gem is reported by GemspecParser as its own
    // BadRequestException("invalidGemFile"),
    // which ErrorHandler maps to 400. Any other IOException here is a storage or spool I/O failure,
    // not something the client sent, so it is left to propagate and surface as the 500 it is.
  }
}
