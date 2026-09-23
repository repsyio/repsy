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

public abstract class ProtocolProcessor implements Comparable<ProtocolProcessor> {
  protected abstract int getPriority();

  protected abstract ProcessorResult process(
      ProtocolContext context,
      HttpServletRequest request,
      HttpServletResponse response,
      Map<String, Object> properties);

  /**
   * Whether this post-processor must still run when the handler threw, so that whatever the request
   * already did before failing (for example, bytes already written to disk) still gets settled even
   * though the client sees the failure. Defaults to {@code false}: a post-processor whose side
   * effect only makes sense for a request that actually succeeded (publishing an artifact-pushed
   * event, for instance) must not run for a failed one. Only a post-processor that explicitly opts
   * in by overriding this to {@code true} runs on failure.
   */
  protected boolean runsOnFailure() {
    return false;
  }

  @Override
  public int compareTo(final ProtocolProcessor processor) {

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
