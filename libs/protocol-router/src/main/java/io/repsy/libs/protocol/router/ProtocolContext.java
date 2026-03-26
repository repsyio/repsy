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
package io.repsy.libs.protocol.router;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

@Getter
public final class ProtocolContext {

  private final @NonNull Map<@NonNull String, @NonNull Object> contextMap;

  public ProtocolContext() {

    this.contextMap = new HashMap<>();
  }

  public void addProperty(final @NonNull String key, final @NonNull Object value) {

    this.contextMap.put(key, value);
  }

  @SuppressWarnings("unchecked")
  public @NonNull <T> T getProperty(final @NonNull String key) {

    return (T) this.contextMap.get(key);
  }
}
