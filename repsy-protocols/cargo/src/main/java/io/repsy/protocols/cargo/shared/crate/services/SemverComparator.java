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
package io.repsy.protocols.cargo.shared.crate.services;

import java.util.Comparator;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class SemverComparator implements Comparator<String> {

  @Override
  public int compare(final String v1, final String v2) {
    final var parts1 = this.splitPreRelease(v1);
    final var parts2 = this.splitPreRelease(v2);

    final var coreCompare = this.compareCore(parts1[0], parts2[0]);

    if (coreCompare != 0) {
      return coreCompare;
    }

    return this.comparePreRelease(parts1[1], parts2[1]);
  }

  private String[] splitPreRelease(final String version) {

    final var idx = version.indexOf('-');

    if (idx == -1) {
      return new String[] {version, ""};
    }

    return new String[] {version.substring(0, idx), version.substring(idx + 1)};
  }

  private int compareCore(final String core1, final String core2) {

    final var segments1 = core1.split("\\.");
    final var segments2 = core2.split("\\.");

    final var len = Math.max(segments1.length, segments2.length);

    for (int i = 0; i < len; i++) {
      final var n1 = i < segments1.length ? this.parseSegment(segments1[i]) : 0;
      final var n2 = i < segments2.length ? this.parseSegment(segments2[i]) : 0;
      final var cmp = Integer.compare(n1, n2);

      if (cmp != 0) {
        return cmp;
      }
    }

    return 0;
  }

  private int comparePreRelease(final String pre1, final String pre2) {

    if (pre1.isEmpty() && pre2.isEmpty()) {
      return 0;
    }

    if (pre1.isEmpty()) {
      return 1;
    }

    if (pre2.isEmpty()) {
      return -1;
    }

    return this.comparePreReleaseIdentifiers(pre1.split("\\."), pre2.split("\\."));
  }

  private int comparePreReleaseIdentifiers(
      final String[] identifiers1, final String[] identifiers2) {

    final var len = Math.max(identifiers1.length, identifiers2.length);

    for (int i = 0; i < len; i++) {

      if (i >= identifiers1.length) {
        return -1;
      }

      if (i >= identifiers2.length) {
        return 1;
      }

      final var cmp = this.compareIdentifiers(identifiers1[i], identifiers2[i]);

      if (cmp != 0) {
        return cmp;
      }
    }

    return 0;
  }

  private int compareIdentifiers(final String id1, final String id2) {

    final var isNum1 = this.isNumeric(id1);
    final var isNum2 = this.isNumeric(id2);

    if (isNum1 && isNum2) {
      return this.compareNumeric(id1, id2);
    }

    if (isNum1) {
      return -1;
    }

    if (isNum2) {
      return 1;
    }

    return id1.compareTo(id2);
  }

  private int compareNumeric(final String id1, final String id2) {
    return Integer.compare(Integer.parseInt(id1), Integer.parseInt(id2));
  }

  private int parseSegment(final String segment) {

    try {
      return Integer.parseInt(segment);
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  private boolean isNumeric(final String s) {

    if (s.isEmpty()) {
      return false;
    }

    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }

    return true;
  }
}
