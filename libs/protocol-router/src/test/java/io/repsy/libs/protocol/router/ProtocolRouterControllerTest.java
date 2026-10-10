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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

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
  void aMethodNoProtocolHandlesIsAnsweredWith405AndTheMethodsThatExist() {
    final var handler =
        new RecordingHandler((context, request, response) -> ResponseEntity.ok().body("ok"));
    final var controller = controllerFor(new RecordingProvider(), handler);

    assertThatThrownBy(
            () -> controller.route(requestFor(HttpMethod.TRACE), mock(HttpServletResponse.class)))
        .isInstanceOfSatisfying(
            HttpRequestMethodNotSupportedException.class,
            ex -> {
              assertThat(ex.getMethod()).isEqualTo("TRACE");
              assertThat(ex.getSupportedMethods()).isNotEmpty().doesNotContain("TRACE");
            });
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

  @Test
  void aHeadNobodyRegisteredIsAnsweredByTheGetHandlerThatAnswersHead() throws Exception {
    final var seen = new ArrayList<Map<String, Object>>();
    final var provider = new RecordingProvider();
    provider.registerPreProcessor(new PropertiesProcessor(seen));
    final var get = new HeadHandler(true, "get-body", "head-answer");
    provider.registerMethodHandler(get);
    final var controller = new ProtocolRouterController(List.of(provider), List.of());

    final var head = controller.route(requestFor(HttpMethod.HEAD), mock(HttpServletResponse.class));

    assertThat(head.getBody()).isEqualTo("head-answer");
    assertThat(seen).containsExactly(Map.of("head", true));

    seen.clear();
    final var got = controller.route(requestFor(HttpMethod.GET), mock(HttpServletResponse.class));

    assertThat(got.getBody()).isEqualTo("get-body");
    assertThat(seen).containsExactly(Map.of("head", false));
  }

  @Test
  void aHandlerRegisteredForHeadWinsOverTheFallback() throws Exception {
    final var provider = new RecordingProvider();
    provider.registerMethodHandler(new HeadHandler(true, "get-body", "fallback"));
    provider.registerMethodHandler(
        new RecordingHandler(
            HttpMethod.HEAD, (context, request, response) -> ResponseEntity.ok().body("own")));
    final var controller = new ProtocolRouterController(List.of(provider), List.of());

    final var head = controller.route(requestFor(HttpMethod.HEAD), mock(HttpServletResponse.class));

    assertThat(head.getBody()).isEqualTo("own");
  }

  @Test
  void aGetHandlerThatDoesNotAnswerHeadLeavesTheHeadUnanswered() {
    final var provider = new RecordingProvider();
    provider.registerMethodHandler(new HeadHandler(false, "get-body", "head-answer"));
    final var controller = new ProtocolRouterController(List.of(provider), List.of());

    assertThatThrownBy(
            () -> controller.route(requestFor(HttpMethod.HEAD), mock(HttpServletResponse.class)))
        .isInstanceOf(HttpRequestMethodNotSupportedException.class);
  }

  @Test
  void aFailingHeadAnswerStillSettlesLikeAFailingHandler() {
    final var settled = new AtomicInteger();
    final var failure = new IllegalStateException("boom");
    final var provider = new RecordingProvider();
    provider.registerPostProcessor(new RecordingProcessor(1, true, settled, null));
    provider.registerMethodHandler(
        new HeadHandler(true, "get-body", "head-answer") {
          @Override
          public ResponseEntity<Object> handleHead(
              final ProtocolContext parsedPath,
              final HttpServletRequest request,
              final HttpServletResponse response) {
            throw failure;
          }
        });
    final var controller = new ProtocolRouterController(List.of(provider), List.of());

    assertThatThrownBy(
            () -> controller.route(requestFor(HttpMethod.HEAD), mock(HttpServletResponse.class)))
        .isSameAs(failure);
    assertThat(settled.get()).isEqualTo(1);
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

    private final HttpMethod method;
    private final HandleFn handleFn;

    private RecordingHandler(final HandleFn handleFn) {
      this(HttpMethod.PUT, handleFn);
    }

    private RecordingHandler(final HttpMethod method, final HandleFn handleFn) {
      this.method = method;
      this.handleFn = handleFn;
    }

    @Override
    public List<HttpMethod> getSupportedMethods() {
      return List.of(this.method);
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

  /** A GET handler; the properties tell a GET from a HEAD to the processors. */
  private static class HeadHandler implements ProtocolMethodHandler {

    private final boolean answersHead;
    private final String getBody;
    private final String headBody;

    private HeadHandler(final boolean answersHead, final String getBody, final String headBody) {
      this.answersHead = answersHead;
      this.getBody = getBody;
      this.headBody = headBody;
    }

    @Override
    public List<HttpMethod> getSupportedMethods() {
      return List.of(HttpMethod.GET);
    }

    @Override
    public Map<String, Object> getProperties() {
      return Map.of("head", false);
    }

    @Override
    public boolean answersHead() {
      return this.answersHead;
    }

    @Override
    public Map<String, Object> getHeadProperties() {
      return Map.of("head", true);
    }

    @Override
    public PathParser getPathParser() {
      return request -> Optional.of(new ProtocolContext());
    }

    @Override
    public ResponseEntity<Object> handle(
        final ProtocolContext parsedPath,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      return ResponseEntity.ok().body(this.getBody);
    }

    @Override
    public ResponseEntity<Object> handleHead(
        final ProtocolContext parsedPath,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      return ResponseEntity.ok().body(this.headBody);
    }
  }

  private static final class PropertiesProcessor extends ProtocolProcessor {

    private final List<Map<String, Object>> seen;

    private PropertiesProcessor(final List<Map<String, Object>> seen) {
      this.seen = seen;
    }

    @Override
    protected int getPriority() {
      return 1;
    }

    @Override
    protected ProcessorResult process(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Map<String, Object> properties) {
      this.seen.add(properties);

      return ProcessorResult.next();
    }
  }
}
