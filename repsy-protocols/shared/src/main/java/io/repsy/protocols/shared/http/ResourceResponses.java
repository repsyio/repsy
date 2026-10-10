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
package io.repsy.protocols.shared.http;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The {@code Content-Disposition} of a downloaded file, built one way for every protocol. Spring
 * quotes the file name and escapes a quote or a backslash in it, so a name never breaks out of the
 * header. Without a header of its own Spring names a download after a path suffix ({@code f.txt}),
 * which is why a download sets one (RPS-1389).
 */
@NullMarked
public final class ResourceResponses {

  private ResourceResponses() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** The header value of a file the client should save under {@code filename}. */
  public static String attachment(final @Nullable String filename) {

    return ContentDisposition.attachment().filename(filename).build().toString();
  }

  /** The header value of a document a browser may show, named {@code filename}. */
  public static String inline(final String filename) {

    return ContentDisposition.inline().filename(filename).build().toString();
  }

  /** The header value of a document a browser may show, with no name; it keeps Spring's out. */
  public static String inline() {

    return ContentDisposition.inline().build().toString();
  }

  /** {@code 200}, {@code application/octet-stream} and an attachment of {@code filename}. */
  public static ResponseEntity.BodyBuilder okAttachment(final @Nullable String filename) {

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename));
  }
}
