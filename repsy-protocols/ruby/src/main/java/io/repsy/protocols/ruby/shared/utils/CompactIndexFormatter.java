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
package io.repsy.protocols.ruby.shared.utils;

import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NullMarked;

/**
 * Generates Compact Index response bodies for /versions, /info/&lt;gem&gt;, and /names.
 *
 * <p>All responses include a preamble: {@code created_at: <ISO8601>\n---\n}.
 */
@UtilityClass
@NullMarked
public class CompactIndexFormatter {

  private static final String SEPARATOR = "---";
  private static final String CREATED_AT_PREFIX = "created_at: ";
  private static final String DEFAULT_PLATFORM = "ruby";

  public static String formatNames(final List<String> gemNames) {
    final var sb = new StringBuilder();
    appendPreamble(sb, Instant.now());
    for (final var name : gemNames) {
      sb.append(name).append('\n');
    }
    return sb.toString();
  }

  public static String formatVersionsIndex(final List<GemCompactEntry> allEntries) {
    final var infoByGem =
        allEntries.stream().collect(Collectors.groupingBy(GemCompactEntry::getGemName));

    final var sb = new StringBuilder();
    appendPreamble(sb, Instant.now());

    infoByGem.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            e -> {
              final var infoBody = buildInfoBody(e.getValue());
              final var md5 = DigestUtils.md5Hex(infoBody);
              sb.append(e.getKey()).append(' ').append(md5).append('\n');
            });

    return sb.toString();
  }

  public static String formatGemInfo(final List<GemCompactEntry> entries) {
    return buildInfoBody(entries);
  }

  /** Computes SHA256 hex of gem file bytes — stored in RubyGemVersion.checksum. */
  public static String sha256Hex(final byte[] gemBytes) {
    return DigestUtils.sha256Hex(gemBytes);
  }

  private static String buildInfoBody(final List<GemCompactEntry> entries) {
    final var lastModified =
        entries.stream()
            .map(GemCompactEntry::getCreatedAt)
            .max(Comparator.naturalOrder())
            .orElse(Instant.EPOCH);
    final var sb = new StringBuilder();
    appendPreamble(sb, lastModified);
    for (final var entry : entries) {
      appendVersionLine(sb, entry);
    }
    return sb.toString();
  }

  private static void appendVersionLine(final StringBuilder sb, final GemCompactEntry entry) {
    if (entry.isYanked()) {
      sb.append('-');
    }
    sb.append(entry.getVersion()).append('|').append(entry.getPlatform()).append('|');

    if (!entry.isYanked()) {
      sb.append(formatDeps(entry.getRuntimeDependencies()));
    }

    sb.append('|').append(entry.getChecksum()).append('\n');
  }

  private static String formatDeps(final List<GemDependency> deps) {
    if (deps.isEmpty()) {
      return "";
    }
    return deps.stream()
        .map(d -> d.getName() + ":" + d.getRequirements())
        .collect(Collectors.joining(","));
  }

  private static void appendPreamble(final StringBuilder sb, final Instant timestamp) {
    sb.append(CREATED_AT_PREFIX).append(timestamp).append('\n');
    sb.append(SEPARATOR).append('\n');
  }
}
