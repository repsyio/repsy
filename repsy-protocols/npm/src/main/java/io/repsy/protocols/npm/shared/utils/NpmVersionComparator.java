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
package io.repsy.protocols.npm.shared.utils;

import java.util.Comparator;

/**
 * Orders npm version strings by semver precedence ({@link NpmSemver}) instead of as plain text
 * (RPS-1688, the npm counterpart of Maven's {@code VersionComparator} from RPS-1665): a database
 * {@code ORDER BY} on the {@code version} column would sort {@code "10.0.0"} above {@code "9.0.0"}.
 * {@code NpmPayloadUtils} already refuses a publish whose version does not parse as semver, so
 * every stored version is one {@link NpmSemver#parse(String)} accepts.
 */
public class NpmVersionComparator implements Comparator<String> {

  @Override
  public int compare(final String v1, final String v2) {
    return NpmSemver.parse(v1).compareTo(NpmSemver.parse(v2));
  }
}
