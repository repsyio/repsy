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
import org.semver4j.Semver;

@NullMarked
public class SemverComparator implements Comparator<String> {

  @Override
  public int compare(final String v1, final String v2) {
    return new Semver(v1).compareTo(new Semver(v2));
  }
}
