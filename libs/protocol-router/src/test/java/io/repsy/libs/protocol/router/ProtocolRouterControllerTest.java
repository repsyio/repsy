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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * RPS-1114: {@code route} decides, centrally, whether a failed request's post-processing still
 * needs to run. A handler that writes bytes and then throws must still let a post-processor that
 * opted in via {@link ProtocolProcessor#runsOnFailure()} settle them, but the exception the client
 * sees must stay exactly what the handler threw — a settlement failure must never mask it.
 */
class ProtocolRouterControllerTest {

  private static HttpServletRequest requestFor(final HttpMethod method) {
    final var request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method.name());
    when(request.getServletPath()).thenReturn("/some/repo/artifact");
    return request;
  }

  private static ProtocolRouterController controllerFor(
      final RecordingProvider provider, final RecordingHandler handler) {
    provider.registerMethodHandler(handler);
    return new ProtocolRouterController(List.of(provider), List.of());
  }

  @Test
  void aHandlerThatThrowsRunsOnlyTheProcessorsThatOptedIntoFailure() {
    final var settled = new AtomicInteger();
    final var skipped = new AtomicInteger();
    final var failure = new IllegalStateException("digest mismatch");

    final var provider = new RecordingProvider();
    provider.registerPostProcessor(new RecordingProcessor(1, true, settled, null));
    provider.registerPostProcessor(new RecordingProcessor(2, false, skipped, null));

    final var handler =
        new RecordingHandler(
            (context, request, response) -> {
              throw failure;
            });
    final var controller = controllerFor(provider, handler);

    assertThatThrownBy(
            () -> controller.route(requestFor(HttpMethod.PUT), mock(HttpServletResponse.class)))
        .isSameAs(failure);

    assertThat(settled.get()).isEqualTo(1);
    assertThat(skipped.get()).isZero();
  }

  @Test
  void aSuccessfulRequestStillRunsEveryPostProcessorRegardlessOfRunsOnFailure() throws Exception {
    final var ranOnFailureOptIn = new AtomicInteger();
    final var ranDefault = new AtomicInteger();

    final var provider = new RecordingProvider();
    provider.registerPostProcessor(new RecordingProcessor(1, true, ranOnFailureOptIn, null));
    provider.registerPostProcessor(new RecordingProcessor(2, false, ranDefault, null));

    final var handler =
        new RecordingHandler((context, request, response) -> ResponseEntity.ok().body("ok"));
    final var controller = controllerFor(provider, handler);

    final var result =
        controller.route(requestFor(HttpMethod.PUT), mock(HttpServletResponse.class));

    assertThat(result.getBody()).isEqualTo("ok");
    assertThat(ranOnFailureOptIn.get()).isEqualTo(1);
    assertThat(ranDefault.get()).isEqualTo(1);
  }

  @Test
  void aSettlementFailureIsSwallowedAndTheOriginalExceptionStillSurfaces() {
    final var handlerFailure = new IllegalStateException("digest mismatch");
    final var settlementFailure = new RuntimeException("usage update failed");

    final var provider = new RecordingProvider();
    provider.registerPostProcessor(
        new RecordingProcessor(1, true, new AtomicInteger(), settlementFailure));

    final var handler =
        new RecordingHandler(
            (context, request, response) -> {
              throw handlerFailure;
            });
    final var controller = controllerFor(provider, handler);

    assertThatThrownBy(
            () -> controller.route(requestFor(HttpMethod.PUT), mock(HttpServletResponse.class)))
        .isSameAs(handlerFailure);
  }

  private static final class RecordingProvider extends ProtocolProvider {

    @Override
    public String getProtocolType() {
      return "test";
    }
  }

  private static final class RecordingProcessor extends ProtocolProcessor {

    private final int priority;
    private final boolean runsOnFailure;
    private final AtomicInteger invocationCount;
    private final @Nullable RuntimeException toThrow;

    private RecordingProcessor(
        final int priority,
        final boolean runsOnFailure,
        final AtomicInteger invocationCount,
        final @Nullable RuntimeException toThrow) {
      this.priority = priority;
      this.runsOnFailure = runsOnFailure;
      this.invocationCount = invocationCount;
      this.toThrow = toThrow;
    }

    @Override
    protected int getPriority() {
      return this.priority;
    }

    @Override
    protected boolean runsOnFailure() {
      return this.runsOnFailure;
    }

    @Override
    protected ProcessorResult process(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Map<String, Object> properties) {

      this.invocationCount.incrementAndGet();

      if (this.toThrow != null) {
        throw this.toThrow;
      }

      return ProcessorResult.next();
    }
  }

  @FunctionalInterface
  private interface HandleFn {
    ResponseEntity<Object> handle(
        ProtocolContext context, HttpServletRequest request, HttpServletResponse response)
        throws Exception;
  }

  private static final class RecordingHandler implements ProtocolMethodHandler {

    private final HandleFn handleFn;

    private RecordingHandler(final HandleFn handleFn) {
      this.handleFn = handleFn;
    }

    @Override
    public List<HttpMethod> getSupportedMethods() {
      return List.of(HttpMethod.PUT);
    }

    @Override
    public Map<String, Object> getProperties() {
      return Map.of();
    }

    @Override
    public PathParser getPathParser() {
      return request -> Optional.of(new ProtocolContext());
    }

    @Override
    public ResponseEntity<Object> handle(
        final ProtocolContext parsedPath,
        final HttpServletRequest request,
        final HttpServletResponse response)
        throws Exception {
      return this.handleFn.handle(parsedPath, request, response);
    }
  }
}
