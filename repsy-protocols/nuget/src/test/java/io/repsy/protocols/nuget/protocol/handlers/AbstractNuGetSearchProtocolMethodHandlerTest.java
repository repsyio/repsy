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
package io.repsy.protocols.nuget.protocol.handlers;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1120: {@code skip} and {@code take} used to be parsed with {@code Integer.parseInt} inside a
 * catch-all that turned a malformed value into a 500 with an ERROR stack trace, and {@code take}
 * had no upper bound.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetSearchProtocolMethodHandler")
class AbstractNuGetSearchProtocolMethodHandlerTest {

  private static final String SEARCH_PATH = "/nuget/v3/search";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private AbstractNuGetSearchProtocolMethodHandler handler;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(AbstractNuGetSearchProtocolMethodHandler.class);

  static class TestHandler extends AbstractNuGetSearchProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.basePathParser, this.facade, this.provider);
    this.logs.start();
    this.logger.addAppender(this.logs);
  }

  @AfterEach
  void tearDown() {
    this.logger.detachAppender(this.logs);
  }

  private static MockHttpServletRequest request(final String query) {
    final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
    request.setServletPath(SEARCH_PATH);
    if (query != null) {
      for (final var param : query.split("&")) {
        final var kv = param.split("=", 2);
        request.setParameter(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
    return request;
  }

  @Test
  @DisplayName("defaults skip to 0 and take to 20 when absent")
  void defaultsWhenParamsAbsent() {
    final var ctx = context(SEARCH_PATH);
    when(this.facade.search(
            eq(ctx), anyString(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
        .thenReturn(new NuGetSearchResponse(0, List.of()));

    final var response = this.handler.handle(ctx, request(null), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).search(eq(ctx), eq(""), eq(0), eq(20), eq(false), eq(false), anyString());
  }

  @Test
  @DisplayName("clamps a huge take to the maximum instead of passing it through unbounded")
  void clampsTakeToMaximum() {
    final var ctx = context(SEARCH_PATH);
    when(this.facade.search(
            eq(ctx), anyString(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
        .thenReturn(new NuGetSearchResponse(0, List.of()));

    final var response =
        this.handler.handle(ctx, request("take=2000000000"), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).search(eq(ctx), eq(""), eq(0), eq(1000), eq(false), eq(false), anyString());
  }

  @ParameterizedTest(name = "semVerLevel={0} opts in: {1}")
  @CsvSource(
      value = {
        "2.0.0, true",
        "2.1.0, true",
        "3.0.0, true",
        "1.0.0, false",
        "1.0, false",
        "0.5, false",
        "abc, false",
        "'', false"
      })
  @DisplayName("opts in to SemVer 2.0.0 only for a semVerLevel of 2.0.0 or more (RPS-1275)")
  void semVerLevel(final String level, final boolean optedIn) {
    final var ctx = context(SEARCH_PATH);
    when(this.facade.search(
            eq(ctx), anyString(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
        .thenReturn(new NuGetSearchResponse(0, List.of()));

    final var response =
        this.handler.handle(
            ctx, request("semVerLevel=" + level.replace("'", "")), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).search(eq(ctx), eq(""), eq(0), eq(20), eq(false), eq(optedIn), anyString());
  }

  @Test
  @DisplayName("does not opt in to SemVer 2.0.0 when the client sends no semVerLevel (RPS-1275)")
  void semVerLevelAbsent() {
    final var ctx = context(SEARCH_PATH);
    when(this.facade.search(
            eq(ctx), anyString(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
        .thenReturn(new NuGetSearchResponse(0, List.of()));

    this.handler.handle(ctx, request("q=x"), new MockHttpServletResponse());

    verify(this.facade).search(eq(ctx), eq("x"), eq(0), eq(20), eq(false), eq(false), anyString());
  }

  @ParameterizedTest(name = "skip={0}")
  @ValueSource(strings = {"abc", "1.5", "-1", "99999999999"})
  @DisplayName("answers 400 for a skip that is not a non-negative integer, without an ERROR log")
  void invalidSkip(final String skip) {
    final var ctx = context(SEARCH_PATH);

    final var response =
        this.handler.handle(ctx, request("skip=" + skip), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(this.facade);
    assertThat(this.logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
  }

  @ParameterizedTest(name = "take={0}")
  @ValueSource(strings = {"abc", "1.5", "-1", "99999999999"})
  @DisplayName("answers 400 for a take that is not a non-negative integer, without an ERROR log")
  void invalidTake(final String take) {
    final var ctx = context(SEARCH_PATH);

    final var response =
        this.handler.handle(ctx, request("take=" + take), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(this.facade);
    assertThat(this.logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
  }

  @Test
  @DisplayName("still answers 500 for an unexpected failure, and still logs it")
  void unexpectedFailureStillLogsAndAnswers500() {
    final var ctx = context(SEARCH_PATH);
    when(this.facade.search(
            eq(ctx), anyString(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
        .thenThrow(new IllegalStateException("storage down"));

    final var response = this.handler.handle(ctx, request(null), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(this.logs.list).anyMatch(event -> event.getLevel() == Level.ERROR);
  }
}
