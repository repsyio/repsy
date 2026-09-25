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
 * The JSON body that answers a publish, a deprecate, an unpublish of one version and the delete of
 * a package: {@code {"ok": true, "id": "<package>", "success": true}}. The public registry answers
 * {@code {"ok": true, "id": ..., "rev": ...}} (and {@code {"success": true}} for a publish); Repsy
 * keeps no document revisions, so it leaves {@code rev} out. Clients that read the body take an
 * answer with neither {@code ok} nor {@code success} for a failure (yarn classic does, see
 * RPS-1362), while npm, pnpm and bun read only the status (RPS-1390).
 */
@NullMarked
final class NpmWriteResponse {

  private NpmWriteResponse() {}

  /**
   * @param scopeName The scope of the package without {@code @}, or {@code null}
   * @param packageName The package name without its scope
   */
  static Map<String, Object> of(final @Nullable String scopeName, final String packageName) {

    final var body = new LinkedHashMap<String, Object>();
    body.put("ok", true);
    body.put("id", scopeName == null ? packageName : "@" + scopeName + "/" + packageName);
    body.put("success", true);

    return body;
  }
}
