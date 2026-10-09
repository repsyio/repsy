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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProtocolProviderTest {

  /**
   * RPS-2050: Two equal-priority processors both run when registered, and the order is stable
   * (ordered by priority, then class name, then identity hash code). Before this fix, the second
   * one was silently dropped because ProtocolProcessor.equals compared by priority only. This test
   * uses two instances of the same class, so ordering is determined by identityHashCode tie-break,
   * which is fine for verifying both processors run.
   */
  @Test
  void twoEqualPriorityProcessorsBothRun() {
    final var provider = new TestProtocolProvider();
    final var invocationOrder = new ArrayList<String>();

    final var processor1 = new TestProcessor(50, invocationOrder, "processor1");
    final var processor2 = new TestProcessor(50, invocationOrder, "processor2");

    provider.registerPreProcessor(processor1);
    provider.registerPreProcessor(processor2);

    final var context = Mockito.mock(ProtocolContext.class);
    final var request = Mockito.mock(HttpServletRequest.class);
    final var response = Mockito.mock(HttpServletResponse.class);
    final var properties = Map.<String, Object>of();

    provider.preProcess(context, request, response, properties);

    assertThat(invocationOrder).hasSize(2).contains("processor1", "processor2");
  }

  /** Verify that two equal-priority processors are ordered stably by class name. */
  @Test
  void equalPriorityProcessorsAreOrderedStably() {
    final var provider = new TestProtocolProvider();
    final var invocationOrder = new ArrayList<String>();

    final var processorA = new AProcessor(50, invocationOrder);
    final var processorB = new BProcessor(50, invocationOrder);

    // Add in reverse order to verify ordering is stable regardless of registration order
    provider.registerPreProcessor(processorB);
    provider.registerPreProcessor(processorA);

    final var context = Mockito.mock(ProtocolContext.class);
    final var request = Mockito.mock(HttpServletRequest.class);
    final var response = Mockito.mock(HttpServletResponse.class);
    final var properties = Map.<String, Object>of();

    provider.preProcess(context, request, response, properties);

    // Even though processorB was registered first, processorA should run first due to class name
    // ordering (AProcessor < BProcessor alphabetically)
    assertThat(invocationOrder).containsExactly("AProcessor", "BProcessor");
  }

  private static final class TestProtocolProvider extends ProtocolProvider {
    @Override
    public String getProtocolType() {
      return "test";
    }
  }

  private static final class TestProcessor extends ProtocolProcessor {
    private final int priority;
    private final List<String> invocationOrder;
    private final String name;

    TestProcessor(final int priority, final List<String> invocationOrder, final String name) {
      this.priority = priority;
      this.invocationOrder = invocationOrder;
      this.name = name;
    }

    @Override
    protected int getPriority() {
      return this.priority;
    }

    @Override
    protected ProcessorResult process(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Map<String, Object> properties) {
      this.invocationOrder.add(this.name);
      return ProcessorResult.next();
    }
  }

  private static final class AProcessor extends ProtocolProcessor {
    private final int priority;
    private final List<String> invocationOrder;

    AProcessor(final int priority, final List<String> invocationOrder) {
      this.priority = priority;
      this.invocationOrder = invocationOrder;
    }

    @Override
    protected int getPriority() {
      return this.priority;
    }

    @Override
    protected ProcessorResult process(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Map<String, Object> properties) {
      this.invocationOrder.add("AProcessor");
      return ProcessorResult.next();
    }
  }

  private static final class BProcessor extends ProtocolProcessor {
    private final int priority;
    private final List<String> invocationOrder;

    BProcessor(final int priority, final List<String> invocationOrder) {
      this.priority = priority;
      this.invocationOrder = invocationOrder;
    }

    @Override
    protected int getPriority() {
      return this.priority;
    }

    @Override
    protected ProcessorResult process(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Map<String, Object> properties) {
      this.invocationOrder.add("BProcessor");
      return ProcessorResult.next();
    }
  }
}
