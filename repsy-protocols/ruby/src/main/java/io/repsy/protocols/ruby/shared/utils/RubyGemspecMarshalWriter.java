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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Produces a Ruby Marshal 4.8 byte stream for a minimal Gem::Specification, suitable for serving
 * GET /quick/Marshal.4.8/{gem}-{version}.gemspec.rz. The output must be compressed with zlib by the
 * caller.
 *
 * <p>The inner structure mirrors what Gem::Specification#_dump writes: an Array of 19 fields. All
 * fields beyond name, version and platform are fixed at minimal/empty values. required_ruby_version
 * and required_rubygems_version are both set to ">= 0" so that matches_current_ruby? always passes.
 *
 * <p>Object references (0x40) and symbol links (0x3b) in the fixed tail are position-dependent and
 * rely on the fixed prefix always emitting exactly the same sequence of objects and symbols.
 */
@UtilityClass
@NullMarked
public class RubyGemspecMarshalWriter {

  // Outer wrapper: 04 08 75 3a 17 "Gem::Specification"
  private static final byte[] OUTER_PREFIX = {
    0x04, 0x08, 0x75, 0x3a, 0x17, 'G', 'e', 'm', ':', ':', 'S', 'p', 'e', 'c', 'i', 'f', 'i', 'c',
    'a', 't', 'i', 'o', 'n'
  };

  // Inner data prefix: marshal header + Array[19] + rubygems_version("3.4.20") + spec_version(4)
  // Symbol table after this: [0]="E"
  private static final byte[] INNER_PREFIX = {
    0x04,
    0x08, // marshal header
    0x5b,
    0x18, // Array of 19 (24 - 5 = 19)
    0x49,
    0x22,
    0x0b, // IVAR String, 6 chars (11 - 5 = 6)
    '3',
    '.',
    '4',
    '.',
    '2',
    '0', // "3.4.20"
    0x06,
    0x3a,
    0x06,
    0x45,
    0x54, // 1 ivar :E true  → registers symbol "E" at index 0
    0x69,
    0x09 // Integer 4 (9 - 5 = 4)
  };

  // Between name and version: U :Gem::Version Array[1] IVAR String (version len+bytes follow)
  // Symbol table after this: [0]="E" [1]="Gem::Version"
  private static final byte[] VERSION_PREFIX = {
    0x55,
    0x3a,
    0x11, // U :Gem::Version (17 - 5 = 12 chars)  → registers "Gem::Version" at [1]
    'G',
    'e',
    'm',
    ':',
    ':',
    'V',
    'e',
    'r',
    's',
    'i',
    'o',
    'n',
    0x5b,
    0x06, // Array[1]
    0x49,
    0x22 // IVAR String (packed version length follows)
  };

  // IVAR string header bytes (0x49=IVAR tag, 0x22=String tag) used when writing name/version inline
  private static final byte[] IVAR_STRING_HEADER = {0x49, 0x22};

  // Trailing 4 bytes for each IVAR string field: 1 ivar, ;E (symlink[0]), false/true
  // 0x06=1 ivar entry, 0x3b=symbol link, 0x00=index 0 ("E"), 0x54=true (UTF-8 encoding)
  private static final byte[] IVAR_SUFFIX_E_TRUE = {0x06, 0x3b, 0x00, 0x54};

  // Everything after name and version:
  //   date (Time.utc(2000,1,1)), summary=nil,
  //   required_ruby_version([">= 0"]), required_rubygems_version([">= 0"] via object ref 11),
  //   original_platform=nil, dependencies=[], rubyforge_project="",
  //   email=nil, authors=[], description=nil, homepage=nil, has_rdoc=true,
  //   new_platform="ruby", licenses=[], metadata={}
  //
  // Symbol indices used here (in order of first appearance in the full inner stream):
  //   [0]="E"  [1]="Gem::Version"  [2]="Time"  [3]="zone"  [4]="Gem::Requirement"
  //
  // Object indices used here (objects 0-7 are from the variable prefix):
  //   [11] = Array[">=" Gem::Version("0")] from required_ruby_version → reused via 0x40 0x10
  private static final byte[] FIXED_TAIL = {
    // date: IVAR u :Time {8 bytes = 2000-01-01 UTC} 1ivar :zone IVAR-String "UTC" 1ivar ;E false
    0x49,
    0x75,
    0x3a,
    0x09,
    'T',
    'i',
    'm',
    'e', // IVAR u :Time — registers "Time" at [2]
    0x0d, // 8 bytes follow (13 - 5 = 8)
    0x20,
    0x00,
    0x19,
    (byte) 0xc0,
    0x00,
    0x00,
    0x00,
    0x00, // 2000-01-01 00:00:00 UTC
    0x06,
    0x3a,
    0x09,
    'z',
    'o',
    'n',
    'e', // 1 ivar :zone — registers "zone" at [3]
    0x49,
    0x22,
    0x08,
    'U',
    'T',
    'C', // IVAR String "UTC" (3 chars)
    0x06,
    0x3b,
    0x00,
    0x46, // 1 ivar ;E false
    // summary = nil
    0x30,
    // required_ruby_version = Gem::Requirement([[">=", Gem::Version("0")]])
    0x55,
    0x3a,
    0x15, // U :Gem::Requirement (21 - 5 = 16 chars) — registers at [4]
    'G',
    'e',
    'm',
    ':',
    ':',
    'R',
    'e',
    'q',
    'u',
    'i',
    'r',
    'e',
    'm',
    'e',
    'n',
    't',
    0x5b,
    0x06, // Array[1]
    0x5b,
    0x06, // Array[1]
    0x5b,
    0x07, // Array[2]  ← object index 11
    0x49,
    0x22,
    0x07,
    '>',
    '=', // IVAR String ">=" (2 chars)
    0x06,
    0x3b,
    0x00,
    0x54, // 1 ivar ;E true
    0x55,
    0x3b,
    0x06, // U ;Gem::Version (symlink[1])
    0x5b,
    0x06, // Array[1]
    0x49,
    0x22,
    0x06,
    '0', // IVAR String "0" (1 char)
    0x06,
    0x3b,
    0x00,
    0x46, // 1 ivar ;E false
    // required_rubygems_version = Gem::Requirement (same ">= 0" via object ref)
    0x55,
    0x3b,
    0x09, // U ;Gem::Requirement (symlink[4])
    0x5b,
    0x06, // Array[1]
    0x5b,
    0x06, // Array[1]
    0x40,
    0x10, // object ref index 11 (16 - 5 = 11) = Array[">=", Gem::Version("0")]
    // original_platform = nil
    0x30,
    // dependencies = []
    0x5b,
    0x00,
    // rubyforge_project = ""
    0x49,
    0x22,
    0x00,
    0x06,
    0x3b,
    0x00,
    0x54,
    // email = nil
    0x30,
    // authors = []
    0x5b,
    0x00,
    // description = nil
    0x30,
    // homepage = nil
    0x30,
    // has_rdoc = true
    0x54,
    // new_platform = "ruby"
    0x49,
    0x22,
    0x09,
    'r',
    'u',
    'b',
    'y', // IVAR String "ruby" (4 chars)
    0x06,
    0x3b,
    0x00,
    0x54, // 1 ivar ;E true
    // licenses = []
    0x5b,
    0x00,
    // metadata = {}
    0x7b,
    0x00
  };

  public static byte[] dumpGemspec(final String name, final String version) {
    try {
      final var nameBytes = name.getBytes(StandardCharsets.UTF_8);
      final var versionBytes = version.getBytes(StandardCharsets.UTF_8);
      final var inner = buildInner(nameBytes, versionBytes);
      final var out = new ByteArrayOutputStream(OUTER_PREFIX.length + 3 + inner.length);
      out.write(OUTER_PREFIX);
      RubyMarshalWriter.writePackedInt(out, inner.length);
      out.write(inner);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] buildInner(final byte[] nameBytes, final byte[] versionBytes)
      throws IOException {
    final var out =
        new ByteArrayOutputStream(
            INNER_PREFIX.length
                + nameBytes.length
                + VERSION_PREFIX.length
                + versionBytes.length
                + FIXED_TAIL.length
                + 12);
    out.write(INNER_PREFIX);
    // name: IVAR String {len} {bytes} 1ivar ;E true
    out.write(IVAR_STRING_HEADER);
    RubyMarshalWriter.writePackedInt(out, nameBytes.length);
    out.write(nameBytes);
    out.write(IVAR_SUFFIX_E_TRUE);
    // version: U :Gem::Version Array[1] IVAR String {len} {bytes} 1ivar ;E true
    out.write(VERSION_PREFIX);
    RubyMarshalWriter.writePackedInt(out, versionBytes.length);
    out.write(versionBytes);
    out.write(IVAR_SUFFIX_E_TRUE);
    out.write(FIXED_TAIL);
    return out.toByteArray();
  }
}
