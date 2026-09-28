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

import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  // After name and version, up to original_platform (array slots 4..7):
  //   date (Time.utc(2000,1,1)), summary=nil,
  //   required_ruby_version([">= 0"]), required_rubygems_version([">= 0"] via object ref 11).
  // Then come original_platform, dependencies (RPS-1554), TAIL_MIDDLE_AFTER_DEPENDENCIES,
  // new_platform and TAIL_END, which no object or symbol reference points into, so the platform
  // strings and the dependencies can vary in length and count freely.
  //
  // Symbol indices used here (in order of first appearance in the full inner stream):
  //   [0]="E"  [1]="Gem::Version"  [2]="Time"  [3]="zone"  [4]="Gem::Requirement"
  //
  // Object indices used here (objects 0-7 are from the variable prefix):
  //   [11] = Array[">=" Gem::Version("0")] from required_ruby_version → reused via 0x40 0x10
  private static final byte[] TAIL_HEAD = {
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
    0x10 // object ref index 11 (16 - 5 = 11) = Array[">=", Gem::Version("0")]
  };

  // original_platform (array slot 8) of a pure gem: nil. RubyGems' Gem::Specification._load does
  // `spec.platform = array[8]`, so this slot decides the platform the client derives
  // `<name>-<version>-<platform>.gem` from (RPS-1553); slot 16 is not read back by _load.
  private static final byte[] ORIGINAL_PLATFORM_NIL = {0x30};

  // Marshal tags used by the dynamic `dependencies` field (RPS-1554); the fixed byte arrays above
  // have their array tags (0x5b) inlined instead, since they never vary.
  private static final int ARRAY_TAG = 0x5b;
  private static final int OBJECT_TAG = 0x6f;
  private static final int USER_MARSHAL_TAG = 0x55;
  private static final int FALSE_TAG = 0x46;
  private static final int DEPENDENCY_IVAR_COUNT = 5;
  private static final String DEFAULT_REQUIREMENT = ">= 0";

  /**
   * Symbols the fixed prefix (up to and including {@code required_rubygems_version}) has already
   * registered, in order, so the {@code dependencies} field can reuse them by link (matching real
   * Ruby's own dump) instead of re-declaring them, and so a link it writes resolves to the same
   * index a real Ruby reader would already have assigned that symbol.
   */
  private static List<String> fixedPrefixSymbols() {
    return new ArrayList<>(List.of("E", "Gem::Version", "Time", "zone", "Gem::Requirement"));
  }

  // rubyforge_project = "" up to has_rdoc = true (array slots 10..15); slot 9 (dependencies) is
  // written dynamically by writeDependencies between original_platform and this.
  private static final byte[] TAIL_MIDDLE_AFTER_DEPENDENCIES = {
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
    0x54
  };

  // new_platform "ruby" (array slot 16) of a pure gem: IVAR String "ruby" (4 chars), 1 ivar ;E true
  private static final byte[] NEW_PLATFORM_RUBY = {
    0x49, 0x22, 0x09, 'r', 'u', 'b', 'y', 0x06, 0x3b, 0x00, 0x54
  };

  // licenses = [] and metadata = {} (array slots 17..18)
  private static final byte[] TAIL_END = {
    // licenses = []
    0x5b,
    0x00,
    // metadata = {}
    0x7b,
    0x00
  };

  private static final String DEFAULT_PLATFORM = "ruby";

  /** The gemspec of a pure-Ruby gem: platform {@code ruby}, no dependencies. */
  public static byte[] dumpGemspec(final String name, final String version) {
    return dumpGemspec(name, version, DEFAULT_PLATFORM);
  }

  /**
   * The gemspec of {@code name} at {@code version} for {@code platform}, with no dependencies.
   * RubyGems derives the {@code .gem} file it downloads from the spec's full name ({@code
   * <name>-<version>-<platform>}), so a platform gem's gemspec has to carry its platform
   * (RPS-1553). {@code ruby} (a pure gem) leaves the bytes as they always were.
   */
  public static byte[] dumpGemspec(final String name, final String version, final String platform) {
    return dumpGemspec(name, version, platform, List.of());
  }

  /**
   * The gemspec of {@code name} at {@code version} for {@code platform}, with its runtime {@code
   * dependencies} written as real {@code Gem::Dependency} objects (RPS-1554): before this, {@code
   * gemspec.rz} always said {@code dependencies = []}, so a client resolving from the quick gemspec
   * (as opposed to the compact index or the {@code .gem} itself) saw no dependency at all.
   */
  public static byte[] dumpGemspec(
      final String name,
      final String version,
      final String platform,
      final List<GemDependency> dependencies) {
    try {
      final var nameBytes = name.getBytes(StandardCharsets.UTF_8);
      final var versionBytes = version.getBytes(StandardCharsets.UTF_8);
      final var inner = buildInner(nameBytes, versionBytes, platform, dependencies);
      final var out = new ByteArrayOutputStream(OUTER_PREFIX.length + 3 + inner.length);
      out.write(OUTER_PREFIX);
      RubyMarshalWriter.writePackedInt(out, inner.length);
      out.write(inner);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] buildInner(
      final byte[] nameBytes,
      final byte[] versionBytes,
      final String platform,
      final List<GemDependency> dependencies)
      throws IOException {
    final var pure = DEFAULT_PLATFORM.equals(platform);
    final var platformBytes = platform.getBytes(StandardCharsets.UTF_8);
    final var out =
        new ByteArrayOutputStream(
            INNER_PREFIX.length
                + nameBytes.length
                + VERSION_PREFIX.length
                + versionBytes.length
                + TAIL_HEAD.length
                + TAIL_MIDDLE_AFTER_DEPENDENCIES.length
                + TAIL_END.length
                + 2 * platformBytes.length
                + 64 * dependencies.size()
                + 32);
    out.write(INNER_PREFIX);
    // name: IVAR String {len} {bytes} 1ivar ;E true
    writeString(out, nameBytes);
    // version: U :Gem::Version Array[1] IVAR String {len} {bytes} 1ivar ;E true
    out.write(VERSION_PREFIX);
    RubyMarshalWriter.writePackedInt(out, versionBytes.length);
    out.write(versionBytes);
    out.write(IVAR_SUFFIX_E_TRUE);
    out.write(TAIL_HEAD);
    final var symbols = fixedPrefixSymbols();
    if (pure) {
      out.write(ORIGINAL_PLATFORM_NIL);
      writeDependencies(out, symbols, dependencies);
      out.write(TAIL_MIDDLE_AFTER_DEPENDENCIES);
      out.write(NEW_PLATFORM_RUBY);
    } else {
      // Both slots carry the platform string: slot 8 is what _load reads, slot 16 is what a
      // reader that does not go through _load would see (the real dump has a Gem::Platform).
      writeString(out, platformBytes);
      writeDependencies(out, symbols, dependencies);
      out.write(TAIL_MIDDLE_AFTER_DEPENDENCIES);
      writeString(out, platformBytes);
    }
    out.write(TAIL_END);
    return out.toByteArray();
  }

  /**
   * Writes the {@code dependencies} field (array slot 9) as an {@code Array} of real {@code
   * Gem::Dependency} objects, matching what {@code Marshal.dump(Gem::Specification)} produces (each
   * one {@code o:Gem::Dependency} with {@code @name}, {@code @requirement}, {@code @type},
   * {@code @prerelease} and {@code @version_requirements}). {@code symbols} is seeded with the
   * class/ivar-encoding symbols the fixed prefix already registered ({@link
   * #fixedPrefixSymbols()}), so a repeated one (such as {@code Gem::Requirement} or the {@code :E}
   * UTF-8 marker every encoded string carries) links back to it instead of being redeclared, the
   * same way real Ruby's own dump does. Nothing after this field links back into it (see the class
   * doc), so it does not itself need to reuse or predict any object (as opposed to symbol) index.
   */
  private static void writeDependencies(
      final ByteArrayOutputStream out, final List<String> symbols, final List<GemDependency> deps)
      throws IOException {
    out.write(ARRAY_TAG);
    RubyMarshalWriter.writePackedInt(out, deps.size());
    for (final var dep : deps) {
      writeDependency(out, symbols, dep);
    }
  }

  private static void writeDependency(
      final ByteArrayOutputStream out, final List<String> symbols, final GemDependency dep)
      throws IOException {
    out.write(OBJECT_TAG);
    RubyMarshalWriter.writeSymbol(out, symbols, "Gem::Dependency");
    RubyMarshalWriter.writePackedInt(out, DEPENDENCY_IVAR_COUNT);
    RubyMarshalWriter.writeSymbol(out, symbols, "@name");
    RubyMarshalWriter.writeEncodedString(out, symbols, dep.getName());
    RubyMarshalWriter.writeSymbol(out, symbols, "@requirement");
    writeRequirement(out, symbols, dep.getRequirements());
    RubyMarshalWriter.writeSymbol(out, symbols, "@type");
    RubyMarshalWriter.writeSymbol(out, symbols, dep.getType());
    RubyMarshalWriter.writeSymbol(out, symbols, "@prerelease");
    out.write(FALSE_TAG);
    // Real Gem::Dependency carries @version_requirements as the same Gem::Requirement instance as
    // @requirement (an old alias for it); a second, independent dump of the same value decodes to
    // an equal, if not object-identical, Gem::Requirement, which no client of this field compares
    // by identity.
    RubyMarshalWriter.writeSymbol(out, symbols, "@version_requirements");
    writeRequirement(out, symbols, dep.getRequirements());
  }

  /**
   * Writes {@code requirements} (formatted by {@code GemspecParser} as one or more {@code "<op>
   * <version>"} clauses joined by {@code ", "}, for example {@code ">= 1.0, < 2.0"}) as a {@code
   * Gem::Requirement}: {@code U :Gem::Requirement} wrapping its {@code marshal_dump}, {@code
   * [@requirements]}, where {@code @requirements} is the array of {@code [operator, Gem::Version]}
   * pairs.
   */
  private static void writeRequirement(
      final ByteArrayOutputStream out, final List<String> symbols, final String requirements)
      throws IOException {
    out.write(USER_MARSHAL_TAG);
    RubyMarshalWriter.writeSymbol(out, symbols, "Gem::Requirement");
    out.write(ARRAY_TAG);
    RubyMarshalWriter.writePackedInt(out, 1);
    final var pairs = parseRequirementPairs(requirements);
    out.write(ARRAY_TAG);
    RubyMarshalWriter.writePackedInt(out, pairs.size());
    for (final var pair : pairs) {
      out.write(ARRAY_TAG);
      RubyMarshalWriter.writePackedInt(out, 2);
      RubyMarshalWriter.writeEncodedString(out, symbols, pair[0]);
      out.write(USER_MARSHAL_TAG);
      RubyMarshalWriter.writeSymbol(out, symbols, "Gem::Version");
      out.write(ARRAY_TAG);
      RubyMarshalWriter.writePackedInt(out, 1);
      RubyMarshalWriter.writeEncodedString(out, symbols, pair[1]);
    }
  }

  /**
   * Splits a {@code GemspecParser}-formatted requirement string on its {@code ", "} clause
   * separator, then each clause on its first space into {@code [operator, version]}. A clause with
   * no space, or an input with no clause at all (a blank string), falls back to {@code
   * DEFAULT_REQUIREMENT} ({@code ">= 0"}), the same default {@code GemspecParser} itself uses for a
   * dependency with no requirement.
   */
  private static List<String[]> parseRequirementPairs(final String requirements) {
    if (requirements.isBlank()) {
      return List.<String[]>of(splitClause(DEFAULT_REQUIREMENT));
    }
    return Arrays.stream(requirements.split(", "))
        .map(RubyGemspecMarshalWriter::splitClause)
        .toList();
  }

  private static String[] splitClause(final String clause) {
    final var trimmed = clause.trim();
    final var spaceIdx = trimmed.indexOf(' ');
    if (spaceIdx < 0) {
      return new String[] {">=", trimmed};
    }
    return new String[] {trimmed.substring(0, spaceIdx), trimmed.substring(spaceIdx + 1).trim()};
  }

  private static void writeString(final ByteArrayOutputStream out, final byte[] bytes)
      throws IOException {
    out.write(IVAR_STRING_HEADER);
    RubyMarshalWriter.writePackedInt(out, bytes.length);
    out.write(bytes);
    out.write(IVAR_SUFFIX_E_TRUE);
  }
}
