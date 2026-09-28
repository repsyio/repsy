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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RubyGemspecMarshalWriter")
class RubyGemspecMarshalWriterTest {

  /** {@code dumpGemspec("demo", "1.2.3")} as it was before platforms were written (RPS-1553). */
  private static final String PURE_DEMO_1_2_3 =
      "0408753a1747656d3a3a53706563696669636174696f6e01b604085b1849220b332e342e3230063a06455469"
          + "0949220964656d6f063b0054553a1147656d3a3a56657273696f6e5b0649220a312e322e33063b0054"
          + "49753a0954696d650d200019c000000000063a097a6f6e65492208555443063b004630553a15"
          + "47656d3a3a526571756972656d656e745b065b065b074922073e3d063b0054553b065b06492206"
          + "30063b0046553b095b065b064010305b00492200063b0054305b0030305449220972756279063b00"
          + "545b007b00";

  @Test
  @DisplayName("a pure gem's gemspec is byte for byte what it always was")
  void pureGemIsUnchanged() {
    final var expected = HexFormat.of().parseHex(PURE_DEMO_1_2_3);

    assertThat(RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3")).isEqualTo(expected);
    assertThat(RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3", "ruby")).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"x86_64-linux", "arm64-darwin", "universal-darwin", "java", "x86-mingw32"})
  @DisplayName("a platform gem's gemspec writes its platform as original_platform and new_platform")
  void platformGemCarriesThePlatform(final String platform) {
    final var dump = RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3", platform);

    final var platformString = ivarString(platform);
    final var first = indexOf(dump, platformString, 0);
    final var second = indexOf(dump, platformString, first + platformString.length);
    assertThat(first).as("original_platform (array slot 8)").isPositive();
    assertThat(second).as("new_platform (array slot 16)").isGreaterThan(first);
    // original_platform sits right before `dependencies = []`, where a pure gem has a nil.
    final var afterFirst = first + platformString.length;
    assertThat(dump[afterFirst]).isEqualTo((byte) 0x5b);
    assertThat(dump[afterFirst + 1]).isEqualTo((byte) 0x00);
    // The pure gem's "ruby" new_platform is gone, and nothing else changed.
    assertThat(indexOf(dump, ivarString("ruby"), 0)).isNegative();
    assertThat(dump.length)
        .isEqualTo(
            RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3").length
                - 1 // the nil original_platform
                - ivarString("ruby").length
                + 2 * platformString.length);
  }

  @Test
  @DisplayName("name, version and platform of any length are framed by the user-defined length")
  void longValuesAreFramed() {
    final var name = "a".repeat(150);
    final var dump = RubyGemspecMarshalWriter.dumpGemspec(name, "10.20.30", "x86_64-linux");

    // `u :Gem::Specification` is followed by the packed length of the inner marshal stream.
    final var prefixLength = 23;
    assertThat(dump[prefixLength]).isEqualTo((byte) 0x02);
    final var inner = (dump[prefixLength + 1] & 0xff) | ((dump[prefixLength + 2] & 0xff) << 8);
    assertThat(dump.length - (prefixLength + 3)).isEqualTo(inner);
  }

  private static byte[] ivarString(final String value) {
    final var bytes = value.getBytes(StandardCharsets.UTF_8);
    final var out = new byte[bytes.length + 7];
    out[0] = 0x49;
    out[1] = 0x22;
    out[2] = (byte) (bytes.length + 5);
    System.arraycopy(bytes, 0, out, 3, bytes.length);
    out[bytes.length + 3] = 0x06;
    out[bytes.length + 4] = 0x3b;
    out[bytes.length + 5] = 0x00;
    out[bytes.length + 6] = 0x54;
    return out;
  }

  private static int indexOf(final byte[] haystack, final byte[] needle, final int from) {
    for (var i = from; i <= haystack.length - needle.length; i++) {
      var match = true;
      for (var j = 0; j < needle.length && match; j++) {
        match = haystack[i + j] == needle[j];
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }

  /**
   * RPS-1554: {@code dependencies} used to be hardcoded to {@code []}, so a client reading the
   * quick gemspec directly (as opposed to the compact index or the {@code .gem} itself, both of
   * which already carried dependencies) saw none. Every fixture here was round-tripped through real
   * Ruby's {@code Marshal.load} (a {@code ruby:3.3-slim} container, not part of this build) to
   * confirm it decodes to a {@code Gem::Specification} with the expected name, type and requirement
   * of each dependency, then pinned byte for byte.
   */
  @Nested
  @DisplayName("dependencies (RPS-1554)")
  class Dependencies {

    /**
     * {@code dumpGemspec("demo", "1.2.3", "ruby", [rake >= 1.0 < 2.0 (runtime), rspec ~> 3.0
     * (development)])}, confirmed by {@code Marshal.load} to decode to those two dependencies with
     * their name, type and requirement intact.
     */
    private static final String DEMO_WITH_TWO_DEPS =
        "0408753a1747656d3a3a53706563696669636174696f6e02fc0104085b1849220b332e342e3230063a06"
            + "4554690949220964656d6f063b0054553a1147656d3a3a56657273696f6e5b0649220a312e322e33"
            + "063b005449753a0954696d650d200019c000000000063a097a6f6e65492208555443063b00463055"
            + "3a1547656d3a3a526571756972656d656e745b065b065b074922073e3d063b0054553b065b064922"
            + "0630063b0046553b095b065b064010305b076f3a1447656d3a3a446570656e64656e63790a3a0a40"
            + "6e616d6549220972616b65063b00543a1140726571756972656d656e74553b095b065b075b074922"
            + "073e3d063b0054553b065b06492208312e30063b00545b074922063c063b0054553b065b06492208"
            + "322e30063b00543a0a40747970653a0c72756e74696d653a104070726572656c65617365463a1a40"
            + "76657273696f6e5f726571756972656d656e7473553b095b065b075b074922073e3d063b0054553b"
            + "065b06492208312e30063b00545b074922063c063b0054553b065b06492208322e30063b00546f3b"
            + "0a0a3b0b49220a7273706563063b00543b0c553b095b065b065b074922077e3e063b0054553b065b"
            + "06492208332e30063b00543b0d3a10646576656c6f706d656e743b0f463b10553b095b065b065b07"
            + "4922077e3e063b0054553b065b06492208332e30063b00544922"
            + "00063b0054305b0030305449220972756279063b00545b007b00";

    @Test
    @DisplayName(
        "writes two dependencies (runtime and development) as real Gem::Dependency objects")
    void writesRuntimeAndDevelopmentDependencies() {
      final var deps =
          List.of(
              GemDependency.builder()
                  .name("rake")
                  .requirements(">= 1.0, < 2.0")
                  .type("runtime")
                  .build(),
              GemDependency.builder()
                  .name("rspec")
                  .requirements("~> 3.0")
                  .type("development")
                  .build());

      final var dump = RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3", "ruby", deps);

      assertThat(dump).isEqualTo(HexFormat.of().parseHex(DEMO_WITH_TWO_DEPS));
    }

    @Test
    @DisplayName("an empty dependency list is byte for byte the pre-RPS-1554 stub")
    void emptyDependenciesUnchanged() {
      final var withEmptyList =
          RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3", "ruby", List.of());
      final var withoutList = RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3", "ruby");
      final var withoutPlatform = RubyGemspecMarshalWriter.dumpGemspec("demo", "1.2.3");

      assertThat(withEmptyList).isEqualTo(withoutList).isEqualTo(withoutPlatform);
    }

    @Test
    @DisplayName("a blank requirement falls back to \">= 0\", matching GemspecParser's own default")
    void blankRequirementDefaultsToAnyVersion() {
      final var deps =
          List.of(GemDependency.builder().name("bare").requirements("").type("runtime").build());

      final var dump = RubyGemspecMarshalWriter.dumpGemspec("demo", "1.0.0", "ruby", deps);

      final var marshal = new String(dump, StandardCharsets.ISO_8859_1);
      assertThat(marshal).contains("bare").contains(">=");
    }

    @Test
    @DisplayName("a platform gem's gemspec still carries its dependencies")
    void platformGemCarriesDependencies() {
      final var deps =
          List.of(
              GemDependency.builder()
                  .name("nokogiri")
                  .requirements(">= 1.10")
                  .type("runtime")
                  .build());

      final var dump =
          RubyGemspecMarshalWriter.dumpGemspec("native-demo", "2.0.0", "x86_64-linux", deps);

      final var marshal = new String(dump, StandardCharsets.ISO_8859_1);
      assertThat(marshal)
          .contains("native-demo")
          .contains("x86_64-linux")
          .contains("nokogiri")
          .contains("Gem::Dependency");
    }
  }
}
