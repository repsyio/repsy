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
package io.repsy.protocols.oci.utils;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * The relative paths ({@code /<name>/...} after {@code /v2/<repo>}) of the OCI routes. Group 1 is
 * the name; group 2 the upload id, digest or reference.
 */
@UtilityClass
@NullMarked
public final class OciPathUtils {

  public static final Pattern UPLOAD_START = Pattern.compile("^/([^/]+)/blobs/uploads/?$");

  public static final Pattern UPLOAD_SESSION =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  public static final Pattern MANIFEST = Pattern.compile("^/([^/]+)/manifests/(.+)$");

  /** A blob path whose digest matches {@code digestRegex}, which each format sets. */
  public static Pattern blob(final String digestRegex) {
    return Pattern.compile("^/([^/]+)/blobs/(" + digestRegex + ")/?$");
  }
}
