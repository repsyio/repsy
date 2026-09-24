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
package io.repsy.protocols.npm.shared.audit;

import io.repsy.protocols.shared.utils.BoundedEntryReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the body of an audit request. npm and bun compress it with gzip, pnpm and yarn send plain
 * JSON, so the body is inflated when the request says so or when it starts with the gzip magic
 * number. The inflated size is bounded, because a small body can inflate to gigabytes.
 */
@UtilityClass
@NullMarked
public class NpmAuditRequestReader {

  /** The most package names a bulk request may carry; the rest is ignored. */
  static final int MAX_PACKAGE_NAMES = 100_000;

  private static final int GZIP_MAGIC_FIRST = 0x1f;
  private static final int GZIP_MAGIC_SECOND = 0x8b;

  /**
   * Reads the JSON object of a request. An empty body is an empty object.
   *
   * @param body The request body, compressed or not
   * @param contentEncoding The {@code Content-Encoding} header, if the request has one
   * @param objectMapper The mapper that parses the JSON
   * @param maxBytes The most bytes the inflated body may have
   * @throws InvalidAuditRequestException If the body is not a JSON object or cannot be inflated
   * @throws io.repsy.protocols.shared.utils.EntryTooLargeException If the inflated body is larger
   *     than {@code maxBytes}
   */
  public static JsonNode read(
      final InputStream body,
      final @Nullable String contentEncoding,
      final ObjectMapper objectMapper,
      final long maxBytes)
      throws IOException {

    final var buffered = new BufferedInputStream(body);
    final var bytes = inflateIfNeeded(buffered, contentEncoding, maxBytes);

    if (bytes.length == 0) {
      return objectMapper.createObjectNode();
    }

    final JsonNode root;
    try {
      root = objectMapper.readTree(bytes);
    } catch (final JacksonException e) {
      throw new InvalidAuditRequestException("the body is not valid JSON", e);
    }

    if (!root.isObject()) {
      throw new InvalidAuditRequestException("the body is not a JSON object");
    }

    return root;
  }

  /**
   * The versions a bulk request asks about: {@code {"name": ["1.0.0", "1.0.1"]}}. A name whose
   * value is not an array is left out, and so is an element that is not a string.
   */
  public static Map<String, Set<String>> bulkVersionsByName(final JsonNode root) {
    final var versionsByName = new LinkedHashMap<String, Set<String>>();

    for (final var entry : root.properties()) {
      if (versionsByName.size() >= MAX_PACKAGE_NAMES) {
        break;
      }

      if (!entry.getValue().isArray()) {
        continue;
      }

      final var versions = new LinkedHashSet<String>();
      for (final var element : entry.getValue()) {
        if (element.isString()) {
          versions.add(element.asString());
        }
      }

      versionsByName.put(entry.getKey(), versions);
    }

    return versionsByName;
  }

  private static byte[] inflateIfNeeded(
      final BufferedInputStream body, final @Nullable String contentEncoding, final long maxBytes)
      throws IOException {

    final var startsWithMagic = startsWithGzipMagic(body);
    final var declaredGzip =
        contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip");

    if (!startsWithMagic && !declaredGzip) {
      return BoundedEntryReader.readAllBytes(body, -1, maxBytes);
    }

    // An empty body with the header is an empty object, not a broken gzip stream.
    body.mark(1);
    if (body.read() < 0) {
      return new byte[0];
    }
    body.reset();

    try (var inflated = new GZIPInputStream(body)) {
      return BoundedEntryReader.readAllBytes(inflated, -1, maxBytes);
    } catch (final IOException e) {
      throw new InvalidAuditRequestException("the body is not valid gzip", e);
    }
  }

  private static boolean startsWithGzipMagic(final BufferedInputStream body) throws IOException {
    body.mark(2);
    final var first = body.read();
    final var second = body.read();
    body.reset();

    return first == GZIP_MAGIC_FIRST && second == GZIP_MAGIC_SECOND;
  }
}
