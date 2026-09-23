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

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Every plausible {@code (name, version, platform)} reading of a {@code <name>-<version>[-
 * <platform>].gem} filename.
 *
 * <p>A gem name may itself contain a {@code -<digit>} sequence (e.g. {@code x-2fa}), which makes
 * the filename genuinely ambiguous by inspection alone: {@code x-2fa-1.0.0.gem} could be gem {@code
 * x-2fa} version {@code 1.0.0}, or gem {@code x} version {@code 2fa-1.0.0}. {@link #split}
 * enumerates every reading, longest name first, so callers can validate each candidate against real
 * stored rows and keep the first (i.e. longest-name) match.
 */
@UtilityClass
@NullMarked
public class GemFilenameCandidates {

  private static final String GEM_EXTENSION = ".gem";
  private static final String DEFAULT_PLATFORM = "ruby";

  /** One candidate reading of a gem filename. */
  public record Candidate(String name, String version, String platform) {}

  /**
   * Splits {@code filename} into every candidate {@link Candidate} reading, ordered longest-name
   * first. Returns an empty list for a filename that does not end in {@code .gem} or that has no
   * {@code -<digit>} boundary at all.
   */
  public static List<Candidate> split(final String filename) {
    if (!filename.endsWith(GEM_EXTENSION)) {
      return List.of();
    }
    final var base = filename.substring(0, filename.length() - GEM_EXTENSION.length());
    final var candidates = new ArrayList<Candidate>();
    for (var i = base.length() - 2; i >= 1; i--) {
      if (base.charAt(i) == '-' && Character.isDigit(base.charAt(i + 1))) {
        addCandidatesForBoundary(candidates, base, i);
      }
    }
    return List.copyOf(candidates);
  }

  private static void addCandidatesForBoundary(
      final List<Candidate> candidates, final String base, final int boundary) {
    final var name = base.substring(0, boundary);
    final var rest = base.substring(boundary + 1);

    candidates.add(new Candidate(name, rest, DEFAULT_PLATFORM));

    final var lastDash = rest.lastIndexOf('-');
    if (lastDash > 0) {
      final var platform = rest.substring(lastDash + 1);
      if (!platform.isEmpty() && !Character.isDigit(platform.charAt(0))) {
        candidates.add(new Candidate(name, rest.substring(0, lastDash), platform));
      }
    }
  }
}
