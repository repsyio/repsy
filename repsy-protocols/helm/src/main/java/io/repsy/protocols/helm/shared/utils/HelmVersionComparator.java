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
package io.repsy.protocols.helm.shared.utils;

import java.util.Comparator;
import org.jspecify.annotations.NullMarked;
import org.semver4j.Semver;

/**
 * Orders chart versions by SemVer 2.0.0 precedence, the order Helm itself sorts them in (a chart
 * version is a SemVer 2 version, and {@code helm repo index} lists the versions of a chart highest
 * first). Build metadata does not count, so two versions that differ only in it are equal here: the
 * comparator breaks that tie on the version string, and one that is not SemVer at all (a row that
 * predates the check) sorts by its string, so the order is total and the same on every call.
 */
@NullMarked
public final class HelmVersionComparator implements Comparator<String> {

  /** Lowest version first. Use {@link Comparator#reversed()} for the newest first. */
  public static final Comparator<String> INSTANCE = new HelmVersionComparator();

  private HelmVersionComparator() {}

  @Override
  public int compare(final String first, final String second) {
    final var a = Semver.parse(first);
    final var b = Semver.parse(second);

    if (a != null && b != null) {
      final var precedence = a.compareTo(b);

      if (precedence != 0) {
        return precedence;
      }
    } else if (a != null) {
      // A version that is not SemVer sorts below every one that is.
      return 1;
    } else if (b != null) {
      return -1;
    }

    return first.compareTo(second);
  }
}
