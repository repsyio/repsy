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
package io.repsy.os.shared.error_handling.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.MfaException;
import io.repsy.core.error_handling.exceptions.RedirectToPathException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Drives {@link ErrorHandler} through real Spring MVC argument resolution, so each test shows which
 * exception class MVC throws and which handler answers it. No Docker or application context needed.
 */
class ErrorHandlerTest {

  private MockMvc mockMvc;

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(ErrorHandler.class);
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController(), new PanelController())
            .setControllerAdvice(new ErrorHandler(new RestResponseFactory(messageSource)))
            .build();

    this.originalLevel = this.handlerLogger.getLevel();
    this.handlerLogger.setLevel(Level.DEBUG);
    this.logEvents.start();
    this.handlerLogger.addAppender(this.logEvents);
  }

  @AfterEach
  void releaseLogs() {
    this.handlerLogger.detachAppender(this.logEvents);
    this.logEvents.stop();
    this.handlerLogger.setLevel(this.originalLevel);
  }

  @Test
  @DisplayName("answers 401 missingRequestHeader for a missing Authorization header")
  void missingAuthorizationHeader() throws Exception {
    this.mockMvc
        .perform(get("/header/authorization"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.text").value("A required request header is missing."))
        .andExpect(jsonPath("$.data").value(HttpHeaders.AUTHORIZATION));
  }

  @Test
  @DisplayName("answers 400 missingRequestHeader for any other missing header")
  void missingOtherHeader() throws Exception {
    this.mockMvc
        .perform(get("/header/other"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
        .andExpect(jsonPath("$.data").value("X-Custom"));
  }

  @Test
  @DisplayName("answers 400 badRequest for a missing cookie")
  void missingCookie() throws Exception {
    this.mockMvc
        .perform(get("/cookie"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("badRequest"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.text").value("Incoming values are not valid."));
  }

  @Test
  @DisplayName("answers 400 badRequest for a missing multipart request part")
  void missingRequestPart() throws Exception {
    this.mockMvc
        .perform(multipart("/part"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("badRequest"));
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a path variable the mapping does not declare")
  void missingPathVariable() throws Exception {
    // A server-side mapping bug rather than a client mistake, so it must not become a 4xx.
    this.mockMvc
        .perform(get("/path-variable"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msgId").value("errorOccurred"));
  }

  @Test
  @DisplayName("answers 405 methodNotSupported with an Allow header for an unsupported HTTP method")
  void methodNotSupported() throws Exception {
    final var result =
        this.mockMvc
            .perform(multipart("/cookie"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.msgId").value("methodNotSupported"))
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET");
  }

  @Test
  @DisplayName(
      "answers 415 unsupportedMediaType with an Accept header for an unsupported body type")
  void mediaTypeNotSupported() throws Exception {
    final var result =
        this.mockMvc
            .perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.msgId").value("unsupportedMediaType"))
            .andExpect(jsonPath("$.type").value("ERROR"))
            .andExpect(jsonPath("$.text").value("Unsupported media type."))
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT))
        .contains(MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  @DisplayName("answers 406 notAcceptable as JSON for an Accept header the endpoint cannot satisfy")
  void mediaTypeNotAcceptable() throws Exception {
    final var result =
        this.mockMvc
            .perform(get("/json-only").accept(MediaType.TEXT_XML))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.msgId").value("notAcceptable"))
            .andExpect(jsonPath("$.type").value("ERROR"))
            .andExpect(
                jsonPath("$.text").value("None of the requested media types can be produced."))
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT))
        .contains(MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  @DisplayName("answers 413 payloadTooLarge for an upload over the size limit")
  void uploadTooLarge() throws Exception {
    this.mockMvc
        .perform(get("/too-large"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.msgId").value("payloadTooLarge"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.text").value("The uploaded content is too large."));
  }

  @Test
  @DisplayName("answers 400 badRequest when the request parameters a mapping requires are missing")
  void unsatisfiedRequestParameter() throws Exception {
    this.mockMvc
        .perform(get("/needs-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("badRequest"));
  }

  @Test
  @DisplayName("does not render 4xx failures of the request when no servlet response is available")
  void clientFailuresWithoutResponse() {
    final var handler =
        new ErrorHandler(new RestResponseFactory(new ResourceBundleMessageSource()));
    final var request = new MockHttpServletRequest();

    assertThat(handler.handleException(new MaxUploadSizeExceededException(1024), request, null))
        .isNull();
    assertThat(
            handler.handleException(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                request,
                null))
        .isNull();
    assertThat(
            handler.handleException(
                new UnsatisfiedServletRequestParameterException(new String[] {"name"}, Map.of()),
                request,
                null))
        .isNull();
  }

  @Test
  @DisplayName("keeps 400 validationError for a body that cannot be read")
  void malformedBody() throws Exception {
    this.mockMvc
        .perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("validationError"));
  }

  @Test
  @DisplayName("answers 500 errorOccurred for a WebClient failure instead of relaying its status")
  void webClientResponseException() throws Exception {
    // The former handler returned a Mono, which a servlet advice turns into an empty 200.
    this.mockMvc
        .perform(get("/web-client"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msgId").value("errorOccurred"));
  }

  @Test
  @DisplayName("answers 400 validationError for a value longer than its column")
  void valueTooLongForColumn() throws Exception {
    this.mockMvc
        .perform(get("/db/22001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("validationError"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.text").value("Incoming data couldn't be validated."))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("answers 409 itemAlreadyExists for a unique constraint violation")
  void uniqueViolation() throws Exception {
    this.mockMvc
        .perform(get("/db/23505"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msgId").value("itemAlreadyExists"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.text").value("The item already exists."))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("answers 409 itemAlreadyExists for the DuplicateKeyException Spring translates to")
  void duplicateKey() throws Exception {
    this.mockMvc
        .perform(get("/db/duplicate-key"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msgId").value("itemAlreadyExists"));
  }

  @ParameterizedTest(name = "SQL state {0}")
  @ValueSource(
      strings = {
        "23502",
        "23503",
        "23513",
        "23514",
        "40001",
        // RPS-1080: the class-22 states other than 22001 stay 500 on purpose. Both databases'
        // spellings are pinned, because PostgreSQL and H2 report the same fault differently:
        // out of range is 22003 on PostgreSQL and 22004 on H2 for an integer column; a text that
        // does not cast is 22P02 on PostgreSQL and 22018 on H2.
        "22003",
        "22004",
        "22P02",
        "22018",
        "22007",
        "22012"
      })
  @DisplayName("keeps 500 errorOccurred for a violation the client cannot correct (RPS-1080)")
  void otherViolationsStayServerErrors(final String sqlState) throws Exception {
    // Not-null, foreign key and check violations mean the server wrote a row it should not have.
    // The class-22 states (numeric out of range, invalid text, division by zero) cannot be caused
    // by a request today, so one that occurs is a server bug that a 500 must keep exposing.
    this.mockMvc
        .perform(get("/db/" + sqlState))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msgId").value("errorOccurred"));
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a violation that carries no SQL state")
  void violationWithoutSqlState() throws Exception {
    this.mockMvc
        .perform(get("/db/no-cause"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msgId").value("errorOccurred"));
  }

  @Test
  @DisplayName("logs a mapped constraint violation as a warning with its SQL state")
  void mappedViolationIsLoggedAsWarning() throws Exception {
    this.mockMvc.perform(get("/db/23505")).andExpect(status().isConflict());

    assertThat(this.logEvents.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .singleElement()
        .asString()
        .startsWith("Constraint violation (SQL state 23505)");
  }

  @Test
  @DisplayName("does not render a constraint violation when no servlet response is available")
  void violationWithoutResponse() {
    final var handler =
        new ErrorHandler(new RestResponseFactory(new ResourceBundleMessageSource()));

    assertThat(
            handler.handleException(
                new DataIntegrityViolationException("boom", new SQLException("boom", "23505")),
                new MockHttpServletRequest(),
                null))
        .isNull();
  }

  @Test
  @DisplayName("answers 503 scanExecutorSaturated for a retryable scan failure")
  void retryableScanFailure() throws Exception {
    this.mockMvc
        .perform(get("/retryable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.msgId").value("scanExecutorSaturated"))
        .andExpect(jsonPath("$.text").value("Vulnerability scanning is busy. Please retry later."));
  }

  @Test
  @DisplayName("answers 301 movedToPath with a readable text and the Location header")
  void movedToPath() throws Exception {
    this.mockMvc
        .perform(get("/moved"))
        .andExpect(status().isMovedPermanently())
        .andExpect(header().string(HttpHeaders.LOCATION, "/new/path"))
        .andExpect(jsonPath("$.msgId").value("movedToPath"))
        .andExpect(jsonPath("$.text").value("The requested resource has moved permanently."));
  }

  @Test
  @DisplayName("falls back to a readable unauthorizedRequest when the exception has no message")
  void unauthorizedWithoutMessage() throws Exception {
    this.mockMvc
        .perform(get("/unauthorized/no-message"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.msgId").value("unauthorizedRequest"))
        .andExpect(jsonPath("$.text").value("Authentication is required to access this resource."));
  }

  @Test
  @DisplayName("falls back to a readable itemAlreadyExists when the exception has no message")
  void itemAlreadyExistsWithoutMessage() throws Exception {
    this.mockMvc
        .perform(get("/conflict/no-message"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msgId").value("itemAlreadyExists"))
        .andExpect(jsonPath("$.text").value("The item already exists."));
  }

  @Test
  @DisplayName("falls back to a readable mfaException when the exception has no message")
  void mfaWithoutMessage() throws Exception {
    this.mockMvc
        .perform(get("/mfa/no-message"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("mfaException"))
        .andExpect(jsonPath("$.text").value("Multi-factor authentication failed."));
  }

  @Test
  @DisplayName("announces the Bearer scheme on a panel 401 for a missing Authorization header")
  void panelMissingAuthorizationHeaderChallenge() throws Exception {
    this.mockMvc
        .perform(get("/panel/header/authorization"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().stringValues(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.msgId").value("missingRequestHeader"));
  }

  @Test
  @DisplayName("announces the Bearer scheme on a panel 401 for a failed token")
  void panelTokenFailureChallenge() throws Exception {
    this.mockMvc
        .perform(get("/panel/unauthorized"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().stringValues(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
  }

  @Test
  @DisplayName("keeps the challenge a panel exception carries instead of adding a second one")
  void panelKeepsOwnChallenge() throws Exception {
    this.mockMvc
        .perform(get("/panel/unauthorized/basic"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().stringValues(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Repsy\""));
  }

  @Test
  @DisplayName("does not announce a challenge on a panel 400 for another missing header")
  void panelOtherMissingHeaderHasNoChallenge() throws Exception {
    this.mockMvc
        .perform(get("/panel/header/other"))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  @DisplayName("does not add the panel challenge to a protocol endpoint's 401")
  void protocolUnauthorizedHasNoPanelChallenge() throws Exception {
    this.mockMvc
        .perform(get("/unauthorized"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
  }

  @Test
  @DisplayName("does not add the panel challenge to a protocol endpoint's missing Authorization")
  void protocolMissingAuthorizationHasNoPanelChallenge() throws Exception {
    this.mockMvc
        .perform(get("/header/authorization"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  @DisplayName("does not render a retryable failure when no servlet response is available")
  void retryableFailureWithoutResponse() {
    final var handler =
        new ErrorHandler(new RestResponseFactory(new ResourceBundleMessageSource()));

    assertThat(
            handler.handleException(
                new RetryableException("scanExecutorSaturated"),
                new MockHttpServletRequest(),
                null))
        .isNull();
  }

  @Test
  @DisplayName(
      "logs a debug message that names the exception when no servlet response is available")
  void debugMessagesDescribeTheirException() throws Exception {
    final var handler =
        new ErrorHandler(new RestResponseFactory(new ResourceBundleMessageSource()));
    final var request = new MockHttpServletRequest();
    final var parameter = new MethodParameter(String.class.getMethod("length"), -1);

    handler.handleException(
        new MethodArgumentNotValidException(
            parameter, new BeanPropertyBindingResult(new Object(), "target")),
        request,
        null);
    handler.handleException(
        new MissingServletRequestParameterException("page", "int"), request, null);
    handler.handleException(new UnAuthorizedException("unAuthorized"), request, null);
    handler.handleException(
        new NoResourceFoundException(HttpMethod.GET, "/missing", "missing"), request, null);

    assertThat(this.logEvents.list)
        .filteredOn(event -> event.getLevel() == Level.DEBUG)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Method argument not valid",
            "Missing request parameter",
            "Unauthorized request",
            "Resource not found");
  }

  @RestController
  static class ThrowingController {

    @GetMapping("/web-client")
    String webClient() {
      throw WebClientResponseException.create(
          404, "upstream", HttpHeaders.EMPTY, "upstream body".getBytes(UTF_8), UTF_8);
    }

    @GetMapping("/header/authorization")
    String authorization(@RequestHeader(HttpHeaders.AUTHORIZATION) final String header) {
      return header;
    }

    @GetMapping("/header/other")
    String other(@RequestHeader("X-Custom") final String header) {
      return header;
    }

    @GetMapping("/cookie")
    String cookie(@CookieValue("sid") final String sid) {
      return sid;
    }

    @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    String json(@RequestBody final Map<String, String> body) {
      return body.toString();
    }

    @PostMapping("/part")
    String part(@RequestPart("file") final MultipartFile file) {
      return file.getName();
    }

    @GetMapping("/path-variable")
    String pathVariable(@PathVariable("id") final String id) {
      return id;
    }

    @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
    String jsonOnly() {
      return "{}";
    }

    @GetMapping("/too-large")
    String tooLarge() {
      throw new MaxUploadSizeExceededException(1024);
    }

    @GetMapping(value = "/needs-param", params = "name")
    String needsParam() {
      return "ok";
    }

    @GetMapping("/retryable")
    String retryable() {
      throw new RetryableException("scanExecutorSaturated");
    }

    @GetMapping("/unauthorized")
    String unauthorized() {
      throw new UnAuthorizedException("accessNotAllowed");
    }

    @GetMapping("/unauthorized/no-message")
    String unauthorizedWithoutMessage() {
      throw new UnAuthorizedException(null);
    }

    @GetMapping("/conflict/no-message")
    String conflictWithoutMessage() {
      throw new ItemAlreadyExistException(null);
    }

    @GetMapping("/mfa/no-message")
    String mfaWithoutMessage() {
      throw new MfaException(null);
    }

    @GetMapping("/db/duplicate-key")
    String duplicateKey() {
      throw new DuplicateKeyException("duplicate key", new SQLException("duplicate", "23505"));
    }

    @GetMapping("/db/no-cause")
    String noCause() {
      throw new DataIntegrityViolationException("no cause");
    }

    @GetMapping("/db/{sqlState}")
    String constraintViolation(@PathVariable("sqlState") final String sqlState) {
      throw new DataIntegrityViolationException(
          "could not execute statement", new SQLException("violation", sqlState));
    }

    @GetMapping("/moved")
    String moved() {
      throw new RedirectToPathException("/new/path");
    }
  }

  /** Stands for the panel API controllers, which are the ones bound to the API port. */
  @RestApiPort("api")
  @RestController
  @RequestMapping("/panel")
  static class PanelController {

    @GetMapping("/header/authorization")
    String authorization(@RequestHeader(HttpHeaders.AUTHORIZATION) final String header) {
      return header;
    }

    @GetMapping("/header/other")
    String other(@RequestHeader("X-Custom") final String header) {
      return header;
    }

    @GetMapping("/unauthorized")
    String unauthorized() {
      throw new UnAuthorizedException("accessNotAllowed");
    }

    @GetMapping("/unauthorized/basic")
    String unauthorizedBasic() {
      throw new UnAuthorizedException(
          "unAuthorized", Map.of(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Repsy\""));
    }
  }
}
