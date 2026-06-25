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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** Encodes gem dependency lists as Ruby Marshal 4.8 streams for the legacy /api/v1/dependencies. */
@UtilityClass
@NullMarked
public class RubyMarshalWriter {

  private static final byte[] EMPTY_ARRAY = {0x04, 0x08, 0x5b, 0x00};
  private static final int HASH_PAIR_COUNT = 4;
  private static final int MARSHAL_MAJOR_VERSION = 0x04;
  private static final int MARSHAL_MINOR_VERSION = 0x08;
  private static final int ARRAY_TAG = 0x5b;
  private static final int HASH_TAG = 0x7b;
  private static final int SYMBOL_LINK_TAG = 0x3b;
  private static final int SYMBOL_NEW_TAG = 0x3a;
  private static final int IVAR_TAG = 0x49;
  private static final int STRING_TAG = 0x22;
  private static final int TRUE_TAG = 0x54;
  private static final int SHORT_INT_MAX = 122;
  private static final int SHORT_INT_OFFSET = 5;
  private static final int BYTE_MAX = 255;
  private static final int BYTE_MASK = 0xff;
  private static final int BYTE_SHIFT = 8;

  public static byte[] dumpDependencies(final List<GemCompactEntry> entries) {
    if (entries.isEmpty()) {
      return EMPTY_ARRAY.clone();
    }
    try {
      final var out = new ByteArrayOutputStream();
      final var symbols = new ArrayList<String>();
      out.write(MARSHAL_MAJOR_VERSION);
      out.write(MARSHAL_MINOR_VERSION);
      writeEntriesArray(out, symbols, entries);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeEntriesArray(
      final ByteArrayOutputStream out,
      final List<String> symbols,
      final List<GemCompactEntry> entries)
      throws IOException {
    out.write(ARRAY_TAG);
    writePackedInt(out, entries.size());
    for (final var entry : entries) {
      writeVersionHash(out, symbols, entry);
    }
  }

  private static void writeVersionHash(
      final ByteArrayOutputStream out, final List<String> symbols, final GemCompactEntry entry)
      throws IOException {
    out.write(HASH_TAG);
    writePackedInt(out, HASH_PAIR_COUNT);
    writeSymbol(out, symbols, "name");
    writeEncodedString(out, symbols, entry.getGemName());
    writeSymbol(out, symbols, "number");
    writeEncodedString(out, symbols, entry.getVersion());
    writeSymbol(out, symbols, "platform");
    writeEncodedString(out, symbols, entry.getPlatform());
    writeSymbol(out, symbols, "dependencies");
    writeDepsArray(out, symbols, entry.getRuntimeDependencies());
  }

  private static void writeDepsArray(
      final ByteArrayOutputStream out, final List<String> symbols, final List<GemDependency> deps)
      throws IOException {
    out.write(ARRAY_TAG);
    writePackedInt(out, deps.size());
    for (final var dep : deps) {
      out.write(ARRAY_TAG);
      writePackedInt(out, 2);
      writeEncodedString(out, symbols, dep.getName());
      writeEncodedString(out, symbols, dep.getRequirements());
    }
  }

  private static void writeSymbol(
      final ByteArrayOutputStream out, final List<String> symbols, final String name)
      throws IOException {
    final var idx = symbols.indexOf(name);
    if (idx >= 0) {
      out.write(SYMBOL_LINK_TAG);
      writePackedInt(out, idx);
    } else {
      symbols.add(name);
      final var bytes = name.getBytes(StandardCharsets.UTF_8);
      out.write(SYMBOL_NEW_TAG);
      writePackedInt(out, bytes.length);
      out.write(bytes);
    }
  }

  private static void writeEncodedString(
      final ByteArrayOutputStream out, final List<String> symbols, final String value)
      throws IOException {
    final var bytes = value.getBytes(StandardCharsets.UTF_8);
    out.write(IVAR_TAG);
    out.write(STRING_TAG);
    writePackedInt(out, bytes.length);
    out.write(bytes);
    writePackedInt(out, 1);
    writeSymbol(out, symbols, "E");
    out.write(TRUE_TAG);
  }

  static void writePackedInt(final ByteArrayOutputStream out, final int value) {
    if (value == 0) {
      out.write(0x00);
    } else if (value <= SHORT_INT_MAX) {
      out.write(value + SHORT_INT_OFFSET);
    } else if (value <= BYTE_MAX) {
      out.write(0x01);
      out.write(value);
    } else {
      out.write(0x02);
      out.write(value & BYTE_MASK);
      out.write((value >> BYTE_SHIFT) & BYTE_MASK);
    }
  }
}
