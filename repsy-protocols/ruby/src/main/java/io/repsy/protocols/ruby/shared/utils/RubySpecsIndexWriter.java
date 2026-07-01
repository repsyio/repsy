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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Encodes gem version lists as Ruby Marshal 4.8 streams for specs.4.8.gz, latest_specs.4.8.gz, and
 * prerelease_specs.4.8.gz. Each entry is a 3-element Array [name, Gem::Version, platform]. Callers
 * must compress the returned bytes with zlib deflate before serving.
 */
@UtilityClass
@NullMarked
public class RubySpecsIndexWriter {

  private static final int MARSHAL_MAJOR = 0x04;
  private static final int MARSHAL_MINOR = 0x08;
  private static final int ARRAY_TAG = 0x5b;
  private static final int USER_DEFINED_TAG = 0x55;
  private static final int IVAR_TAG = 0x49;
  private static final int STRING_TAG = 0x22;
  private static final int TRUE_TAG = 0x54;
  private static final int SYMBOL_NEW_TAG = 0x3a;
  private static final int SYMBOL_LINK_TAG = 0x3b;
  private static final String GEM_VERSION_SYMBOL = "Gem::Version";
  private static final String E_SYMBOL = "E";
  private static final int ENTRY_ELEMENT_COUNT = 3;

  /** Returns Marshal bytes for all entries (caller must pass only non-yanked versions). */
  public static byte[] dumpSpecs(final List<GemCompactEntry> entries) {
    return marshal(entries);
  }

  /** Returns Marshal bytes for non-yanked prerelease versions (version contains a letter). */
  public static byte[] dumpPrereleaseSpecs(final List<GemCompactEntry> entries) {
    return marshal(entries.stream().filter(e -> isPrerelease(e.getVersion())).toList());
  }

  /** Returns Marshal bytes for the latest non-prerelease non-yanked version per gem. */
  public static byte[] dumpLatestSpecs(final List<GemCompactEntry> entries) {
    final var latestByGem =
        entries.stream()
            .filter(e -> !isPrerelease(e.getVersion()))
            .collect(
                Collectors.toMap(
                    GemCompactEntry::getGemName,
                    e -> e,
                    (a, b) -> b.getCreatedAt().isAfter(a.getCreatedAt()) ? b : a,
                    LinkedHashMap::new));
    return marshal(new ArrayList<>(latestByGem.values()));
  }

  private static byte[] marshal(final List<GemCompactEntry> entries) {
    try {
      final var out = new ByteArrayOutputStream();
      final var symbols = new ArrayList<String>();
      out.write(MARSHAL_MAJOR);
      out.write(MARSHAL_MINOR);
      out.write(ARRAY_TAG);
      RubyMarshalWriter.writePackedInt(out, entries.size());
      for (final var entry : entries) {
        writeEntry(out, symbols, entry);
      }
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeEntry(
      final ByteArrayOutputStream out, final List<String> symbols, final GemCompactEntry entry)
      throws IOException {
    out.write(ARRAY_TAG);
    RubyMarshalWriter.writePackedInt(out, ENTRY_ELEMENT_COUNT);
    writeEncodedString(out, symbols, entry.getGemName());
    writeGemVersion(out, symbols, entry.getVersion());
    writeEncodedString(out, symbols, entry.getPlatform());
  }

  private static void writeGemVersion(
      final ByteArrayOutputStream out, final List<String> symbols, final String version)
      throws IOException {
    out.write(USER_DEFINED_TAG);
    writeSymbol(out, symbols, GEM_VERSION_SYMBOL);
    out.write(ARRAY_TAG);
    RubyMarshalWriter.writePackedInt(out, 1);
    writeEncodedString(out, symbols, version);
  }

  private static void writeEncodedString(
      final ByteArrayOutputStream out, final List<String> symbols, final String value)
      throws IOException {
    final var bytes = value.getBytes(StandardCharsets.UTF_8);
    out.write(IVAR_TAG);
    out.write(STRING_TAG);
    RubyMarshalWriter.writePackedInt(out, bytes.length);
    out.write(bytes);
    RubyMarshalWriter.writePackedInt(out, 1);
    writeSymbol(out, symbols, E_SYMBOL);
    out.write(TRUE_TAG);
  }

  private static void writeSymbol(
      final ByteArrayOutputStream out, final List<String> symbols, final String name)
      throws IOException {
    final var idx = symbols.indexOf(name);
    if (idx >= 0) {
      out.write(SYMBOL_LINK_TAG);
      RubyMarshalWriter.writePackedInt(out, idx);
      return;
    }
    symbols.add(name);
    final var bytes = name.getBytes(StandardCharsets.UTF_8);
    out.write(SYMBOL_NEW_TAG);
    RubyMarshalWriter.writePackedInt(out, bytes.length);
    out.write(bytes);
  }

  private static boolean isPrerelease(final String version) {
    return version.chars().anyMatch(Character::isLetter);
  }
}
