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
package io.repsy.protocols.maven.shared.utils;

import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/** Where the files of a group and of an artifact sit inside a repo's storage directory. */
@UtilityClass
public class MavenStoragePathUtils {

  /**
   * The directories of a group, deepest first: {@code io.repsy.test} gives {@code io/repsy/test},
   * {@code io/repsy} and {@code io}.
   */
  public static Path[] groupPaths(final String groupId) {

    final var paths = groupId.split("\\.", -1);
    final var groupPaths = new Path[paths.length];

    var path = Path.of(paths[0]);
    groupPaths[paths.length - 1] = path;

    for (int i = 1; i < paths.length; i++) {
      path = path.resolve(paths[i]).normalize();
      groupPaths[paths.length - 1 - i] = path;
    }

    return groupPaths;
  }

  /** The directory of an artifact, {@code <group as path>/<artifactId>}. */
  public static Path artifactPath(final String groupId, final String artifactId) {

    final var paths = groupId.split("\\.", -1);

    var path = Path.of(paths[0]);

    for (int i = 1; i < paths.length; ++i) {
      path = path.resolve(paths[i]);
    }

    return path.resolve(artifactId).normalize();
  }
}
