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
package io.repsy.protocols.maven.protocol.handlers;

import io.repsy.protocols.maven.protocol.resources.SynthesizedFileResource;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The headers a Maven path answers with, shared by the {@code GET} and the {@code HEAD} handler so
 * the two cannot drift (RPS-1368): a directory listing is {@code text/html}, a file is an
 * attachment of {@code application/octet-stream}. A generated file ({@link
 * SynthesizedFileResource}) is held in memory like a listing, but it is a file (RPS-1369).
 */
@NullMarked
final class MavenResourceResponses {

  private MavenResourceResponses() {}

  static ResponseEntity.BodyBuilder ok(final Resource resource) {

    final var builder = ResponseEntity.ok();

    if (resource instanceof ByteArrayResource && !(resource instanceof SynthesizedFileResource)) {
      builder.contentType(MediaType.TEXT_HTML);
    } else {
      builder.contentType(MediaType.APPLICATION_OCTET_STREAM);
      builder.header(
          HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + resource.getFilename());
    }

    return builder;
  }
}
