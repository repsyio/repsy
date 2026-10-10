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
package io.repsy.os.shared.error_handling.advice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.shared.error_handling.services.ErrorResponseService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.HttpClientErrorException;

/**
 * RPS-2174: the error handlers used to log the whole exception, and the upstream response body, so
 * a request value (token, password) that a message embeds ended up in the log. No log event, at any
 * level, may carry such a value.
 */
@DisplayName("Error handlers keep request values out of the log")
class ErrorAdviceLogLeakTest {

  private static final String SECRET = "s3cr3t-token-value";

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final List<Logger> loggers =
      List.of(
          (Logger) LoggerFactory.getLogger(PanelProblemDetailAdvice.class),
          (Logger) LoggerFactory.getLogger(ProtocolErrorAdvice.class),
          (Logger) LoggerFactory.getLogger(ErrorResponseService.class));
  private final List<Level> originalLevels = new ArrayList<>();

  private PanelProblemDetailAdvice panel;
  private ProtocolErrorAdvice protocol;
  private ErrorResponseService errors;

  @BeforeEach
  void setUp() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");

    this.errors = new ErrorResponseService(new RestResponseFactory(messageSource));
    this.panel = new PanelProblemDetailAdvice(this.errors);
    this.protocol = new ProtocolErrorAdvice(this.errors);

    this.logEvents.start();

    for (final var logger : this.loggers) {
      this.originalLevels.add(logger.getLevel());
      logger.setLevel(Level.TRACE);
      logger.addAppender(this.logEvents);
    }
  }

  @AfterEach
  void tearDown() {
    for (int i = 0; i < this.loggers.size(); i++) {
      this.loggers.get(i).detachAppender(this.logEvents);
      this.loggers.get(i).setLevel(this.originalLevels.get(i));
    }

    this.logEvents.stop();
  }

  private void assertNothingLoggedSecret() {
    assertThat(this.logEvents.list).isNotEmpty();

    for (final var event : this.logEvents.list) {
      final var text =
          event.getFormattedMessage()
              + (event.getThrowableProxy() != null
                  ? ThrowableProxyUtil.asString(event.getThrowableProxy())
                  : "");

      assertThat(text).doesNotContain(SECRET);
    }
  }

  private static ConversionFailedException conversionFailed() {
    return new ConversionFailedException(
        TypeDescriptor.valueOf(String.class),
        TypeDescriptor.valueOf(Integer.class),
        SECRET,
        new NumberFormatException("For input string: \"" + SECRET + "\""));
  }

  private static DataIntegrityViolationException integrityViolation() {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new java.sql.SQLException("Key (token)=(" + SECRET + ") already exists"));
  }

  @Test
  @DisplayName("conversion failure without a response (panel)")
  void conversionFailedWithoutResponse() {
    this.panel.handleConversionFailed(conversionFailed(), new MockHttpServletRequest(), null);

    assertNothingLoggedSecret();
  }

  @Test
  @DisplayName("conversion failure with a response (panel)")
  void conversionFailedWithResponse() {
    this.panel.handleConversionFailed(
        conversionFailed(), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertNothingLoggedSecret();
  }

  @Test
  @DisplayName("data integrity violation without a response (protocol)")
  void dataIntegrityWithoutResponse() {
    this.protocol.handleDataIntegrityViolation(
        integrityViolation(), new MockHttpServletRequest(), null);

    assertNothingLoggedSecret();
  }

  @Test
  @DisplayName("data integrity violation with a response (protocol)")
  void dataIntegrityWithResponse() {
    this.protocol.handleDataIntegrityViolation(
        integrityViolation(), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertNothingLoggedSecret();
  }

  @Test
  @DisplayName("fallback without a response")
  void fallbackWithoutResponse() {
    this.errors.fallback(integrityViolation(), new MockHttpServletRequest(), null);

    assertNothingLoggedSecret();
  }

  @Test
  @DisplayName("fallback of an upstream client error does not log its body")
  void fallbackUpstreamBody() {
    final var upstream =
        HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST,
            "Bad Request",
            new HttpHeaders(),
            ("{\"echo\":\"" + SECRET + "\"}").getBytes(UTF_8),
            UTF_8);

    this.errors.fallback(upstream, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertNothingLoggedSecret();
    assertThat(this.logEvents.list)
        .anyMatch(event -> event.getFormattedMessage().contains("HTTP 400"));
  }
}
