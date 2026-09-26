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
package io.repsy.protocols.golang.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The headers a Go proxy path answers with, shared by the {@code GET} and the {@code HEAD} handler
 * so the two cannot drift (RPS-1465).
 */
@NullMarked
final class GoDownloadResponses {

  private GoDownloadResponses() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * The status line and headers of an existing file; the {@code GET} adds the resource as the body.
   */
  static ResponseEntity.BodyBuilder ok(final ProtocolContext context) {
    final var responseBuilder = ResponseEntity.ok().contentType(resolveContentType(context));
    final var contentDisposition = resolveContentDisposition(context);

    if (contentDisposition != null) {
      responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
    }

    return responseBuilder;
  }

  /**
   * The status line and headers of what the proxy does not have. The GOPROXY protocol wants a 404
   * (or 410) and a text/plain body, which the go command prints as the reason when no other GOPROXY
   * entry has it either (RPS-1428). The header keeps Spring from naming the reason "f.txt" when the
   * URL ends in {@code .zip}, {@code .info} or {@code .mod} (RPS-1442).
   */
  static ResponseEntity.BodyBuilder notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString());
  }

  static String notFoundText(final ProtocolContext context) {
    return "not found: " + ProtocolContextUtils.getRelativePath(context).getPath();
  }

  /**
   * The module zip is an attachment, {@code .info} and {@code .mod} are shown under their own name.
   * Without a header of its own Spring names all three "f.txt" (RPS-1389). {@code @v/list} and
   * {@code @latest} have no extension, so Spring adds nothing to them.
   */
  private static @Nullable String resolveContentDisposition(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var filename = path.substring(path.lastIndexOf('/') + 1);

    if (filename.endsWith(".zip")) {
      return ContentDisposition.attachment().filename(filename).build().toString();
    }

    if (filename.endsWith(".info") || filename.endsWith(".mod")) {
      return ContentDisposition.inline().filename(filename).build().toString();
    }

    return null;
  }

  private static MediaType resolveContentType(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();

    if (path.endsWith("/@v/list") || path.endsWith(".mod")) {
      return MediaType.TEXT_PLAIN;
    }

    if (path.endsWith(".info") || path.endsWith("/@latest")) {
      return MediaType.APPLICATION_JSON;
    }

    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
