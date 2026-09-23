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

import io.repsy.protocols.ruby.shared.utils.GemFilenameCandidates.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GemFilenameCandidates")
class GemFilenameCandidatesTest {

  @Test
  @DisplayName("a plain name-version filename yields exactly one candidate")
  void plainFilenameYieldsOneCandidate() {
    assertThat(GemFilenameCandidates.split("demo-1.2.3.gem"))
        .containsExactly(new Candidate("demo", "1.2.3", "ruby"));
  }

  @Test
  @DisplayName("a filename with a platform suffix includes the platform reading")
  void platformFilenameIncludesPlatformReading() {
    assertThat(GemFilenameCandidates.split("demo-1.2.3-java.gem"))
        .contains(new Candidate("demo", "1.2.3", "java"));
  }

  @Test
  @DisplayName("a hyphen-digit gem name is tried before the greedy-short reading")
  void hyphenDigitNameTriedFirst() {
    final var candidates = GemFilenameCandidates.split("x-2fa-1.0.0.gem");

    assertThat(candidates)
        .containsExactly(
            new Candidate("x-2fa", "1.0.0", "ruby"), new Candidate("x", "2fa-1.0.0", "ruby"));
  }

  @Test
  @DisplayName("a hyphen-digit gem name with a platform suffix still resolves correctly")
  void hyphenDigitNameWithPlatform() {
    assertThat(GemFilenameCandidates.split("x-2fa-1.0.0-java.gem"))
        .contains(new Candidate("x-2fa", "1.0.0", "java"));
  }

  @Test
  @DisplayName("a name with no -<digit> boundary yields no candidates")
  void nameWithNoBoundaryYieldsNothing() {
    assertThat(GemFilenameCandidates.split("rack.gem")).isEmpty();
  }

  @Test
  @DisplayName("a non-.gem filename yields no candidates")
  void nonGemFilenameYieldsNothing() {
    assertThat(GemFilenameCandidates.split("demo-1.2.3.tar.gz")).isEmpty();
  }
}
