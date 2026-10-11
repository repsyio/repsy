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
package io.repsy.protocols.pypi.shared.utils;

import java.util.Comparator;

/**
 * Orders PyPI release versions by PEP 440 precedence ({@link Pep440Version}) instead of as plain
 * text (RPS-1688): a database {@code ORDER BY} on the {@code version} column would sort {@code
 * "10.0"} above {@code "9.0"}, and a pre-release like {@code "2.0.0a1"} after the final release
 * {@code "2.0.0"} it precedes.
 */
public class PypiVersionComparator implements Comparator<String> {

  @Override
  public int compare(final String v1, final String v2) {
    return Pep440Version.parse(v1).compareTo(Pep440Version.parse(v2));
  }
}
