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
package io.repsy.protocols.npm.protocol.handlers;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The JSON body that answers a dist-tag change: {@code {"ok": true, "id": "<package>", "dist-tags":
 * {...}}}. The public registry answers {@code {"ok": true, "id": ..., "rev": ...}}; Repsy keeps no
 * document revisions, so it says the tags the package has now instead. Clients read {@code ok}:
 * yarn classic takes an answer without it for a failure, while npm and pnpm read only the status.
 */
@NullMarked
final class NpmDistTagsResponse {

  private NpmDistTagsResponse() {}

  /**
   * @param scopeName The scope of the package without {@code @}, or {@code null}
   * @param packageName The package name without its scope
   * @param distTags The tags of the package after the change
   */
  static Map<String, Object> of(
      final @Nullable String scopeName,
      final String packageName,
      final Map<String, String> distTags) {

    final var body = new LinkedHashMap<String, Object>();
    body.put("ok", true);
    body.put("id", scopeName == null ? packageName : "@" + scopeName + "/" + packageName);
    body.put("dist-tags", distTags);

    return body;
  }
}
