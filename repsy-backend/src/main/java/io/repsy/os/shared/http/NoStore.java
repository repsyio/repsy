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
package io.repsy.os.shared.http;

import jakarta.servlet.http.HttpServletResponse;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;

/**
 * Marks a response that carries a secret (a token, a password) so no browser cache, proxy or shared
 * cache keeps a copy of it (API guideline, "Headers and caching").
 */
@UtilityClass
@NullMarked
public final class NoStore {

  /**
   * Sets {@code Cache-Control: no-store} on the response.
   *
   * @param response The response that is about to carry the secret
   */
  public static void apply(final HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
  }
}
