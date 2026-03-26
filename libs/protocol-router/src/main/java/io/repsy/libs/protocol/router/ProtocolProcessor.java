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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public abstract class ProtocolProcessor implements Comparable<ProtocolProcessor> {
  protected abstract int getPriority();

  protected abstract @NonNull ProcessorResult process(
      @NonNull ProtocolContext context,
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Map<@NonNull String, @NonNull Object> properties);

  @Override
  public int compareTo(final @NonNull ProtocolProcessor processor) {
    return Integer.compare(this.getPriority(), processor.getPriority());
  }

  @Override
  public boolean equals(final Object obj) {
    return obj instanceof final ProtocolProcessor pp && this.compareTo(pp) == 0;
  }

  @Override
  public int hashCode() {
    return this.getPriority();
  }
}
