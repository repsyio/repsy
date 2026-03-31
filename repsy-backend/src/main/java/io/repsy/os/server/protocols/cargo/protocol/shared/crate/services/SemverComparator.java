package io.repsy.os.server.protocols.cargo.protocol.shared.crate.services;

import java.util.Comparator;

public class SemverComparator implements Comparator<String> {

  @Override
  public int compare(final String v1, final String v2) {
    final var parts1 = splitPreRelease(v1);
    final var parts2 = splitPreRelease(v2);

    final var coreCompare = compareCore(parts1[0], parts2[0]);

    if (coreCompare != 0) {
      return coreCompare;
    }

    return comparePreRelease(parts1[1], parts2[1]);
  }

  private String[] splitPreRelease(final String version) {
    final var idx = version.indexOf('-');
    if (idx == -1) {
      return new String[]{version, ""};
    }
    return new String[]{version.substring(0, idx), version.substring(idx + 1)};
  }

  private int compareCore(final String core1, final String core2) {
    final var segments1 = core1.split("\\.");
    final var segments2 = core2.split("\\.");

    final var len = Math.max(segments1.length, segments2.length);

    for (int i = 0; i < len; i++) {
      final var n1 = i < segments1.length ? parseSegment(segments1[i]) : 0;
      final var n2 = i < segments2.length ? parseSegment(segments2[i]) : 0;
      final var cmp = Integer.compare(n1, n2);
      if (cmp != 0) {
        return cmp;
      }
    }

    return 0;
  }

  private int comparePreRelease(final String pre1, final String pre2) {
    if (pre1.isEmpty() && pre2.isEmpty()) return 0;
    if (pre1.isEmpty()) return 1;
    if (pre2.isEmpty()) return -1;

    final var identifiers1 = pre1.split("\\.");
    final var identifiers2 = pre2.split("\\.");

    final var len = Math.max(identifiers1.length, identifiers2.length);

    for (int i = 0; i < len; i++) {
      if (i >= identifiers1.length) return -1;
      if (i >= identifiers2.length) return 1;

      final var id1 = identifiers1[i];
      final var id2 = identifiers2[i];

      final var isNum1 = isNumeric(id1);
      final var isNum2 = isNumeric(id2);

      if (isNum1 && isNum2) {
        final var cmp = Integer.compare(Integer.parseInt(id1), Integer.parseInt(id2));
        if (cmp != 0) return cmp;
      } else if (isNum1) {
        return -1;
      } else if (isNum2) {
        return 1;
      } else {
        final var cmp = id1.compareTo(id2);
        if (cmp != 0) return cmp;
      }
    }

    return 0;
  }

  private int parseSegment(final String segment) {
    try {
      return Integer.parseInt(segment);
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  private boolean isNumeric(final String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }
}
