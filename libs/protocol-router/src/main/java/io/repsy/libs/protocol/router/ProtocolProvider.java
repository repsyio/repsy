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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.http.HttpMethod;

public abstract class ProtocolProvider {

  private final Map<HttpMethod, List<SpecifiedProtocolMethodHandler>> methodHandlersMap =
      new HashMap<>();

  private final SortedSet<ProtocolProcessor> preProcessors = new TreeSet<>();
  private final SortedSet<ProtocolProcessor> postProcessors = new TreeSet<>();

  public ProcessorResult preProcess(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    for (final var processor : this.preProcessors) {
      final var result = processor.process(context, request, response, properties);

      if (!result.isEmpty()) {
        return result;
      }
    }

    return ProcessorResult.next();
  }

  public ProcessorResult postProcess(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    for (final var processor : this.postProcessors) {
      final var result = processor.process(context, request, response, properties);

      if (!result.isEmpty()) {
        return result;
      }
    }

    return ProcessorResult.next();
  }

  public void registerMethodHandler(final ProtocolMethodHandler methodHandler) {
    final var supportedMethods = methodHandler.getSupportedMethods();
    final var protocolType = this.getProtocolType();

    for (final var method : supportedMethods) {
      final var handler = new SpecifiedProtocolMethodHandler(method, protocolType, methodHandler);
      this.methodHandlersMap.computeIfAbsent(method, _ -> new ArrayList<>()).add(handler);
    }
  }

  public void registerPreProcessor(final ProtocolProcessor protocolProcessor) {
    this.preProcessors.add(protocolProcessor);
  }

  public void registerPostProcessor(final ProtocolProcessor protocolProcessor) {
    this.postProcessors.add(protocolProcessor);
  }

  Map<HttpMethod, List<SpecifiedProtocolMethodHandler>> getMethodHandlersMap() {
    return this.methodHandlersMap;
  }

  public abstract String getProtocolType();
}
