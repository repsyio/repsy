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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.MfaException;
import io.repsy.core.error_handling.exceptions.RedirectToPathException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.core.web.paging.InvalidPagingParameterException;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.StorageUnavailableException;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * RPS-2063: characterization of every exception the error advices of this package answer. The
 * exceptions go through a real {@link ExceptionHandlerExceptionResolver} over the advice beans of
 * the package, so it is the advice order and the exception type resolution that are pinned, not
 * only the handler bodies: when the advices are split or reordered the status, the content type,
 * the headers and the body of every row must stay the same.
 *
 * <p>The expectation is the {@code error-advice-characterization.txt} resource, one row per
 * exception and request kind (panel, panel port without a controller, protocol, OCI, Cargo).
 * Regenerate it deliberately with {@code -Dcharacterization.write=true} and review the diff.
 */
class ErrorAdviceCharacterizationTest {

  private static final String RESOURCE = "error-advice-characterization.txt";

  private static final java.util.regex.Pattern UUID_PATTERN =
      java.util.regex.Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** The local port of a request that no controller served but that arrived on the panel port. */
  private static final int API_PORT = 8081;

  private static AnnotationConfigApplicationContext context;
  private static ExceptionHandlerExceptionResolver resolver;

  @BeforeAll
  static void startAdvices() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean("messageSource", StaticMessageSource.class);
    context.registerBean(RestResponseFactory.class);
    context.registerBean(
        MultiPortProperties.class,
        () -> {
          final var props = new MultiPortProperties();
          props.setPorts(Map.of("api", API_PORT));
          return props;
        });
    context.scan("io.repsy.os.shared.error_handling");
    context.refresh();

    resolver = new ExceptionHandlerExceptionResolver();
    resolver.setMessageConverters(
        List.of(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()));
    resolver.setApplicationContext(context);
    resolver.afterPropertiesSet();
  }

  @AfterAll
  static void stopAdvices() {
    context.close();
  }

  @Test
  @DisplayName("every exception is answered exactly as the recorded status, headers and body")
  void everyExceptionIsAnsweredAsRecorded() throws Exception {
    final var actual = new ArrayList<String>();

    for (final var entry : cases().entrySet()) {
      for (final var kind : Kind.values()) {
        actual.add(answer(entry.getKey(), entry.getValue().get(), kind));
      }
    }

    // A storage outage on a Cargo request is rewritten to the Cargo body by CargoErrorBodyAdvice.
    actual.add(
        answer(
            "StorageUnavailable",
            new StorageUnavailableException("disk", new IOException("disk")),
            Kind.CARGO));

    final var path = Path.of("src", "test", "resources", RESOURCE);

    if (Boolean.getBoolean("characterization.write")) {
      Files.write(path, actual, StandardCharsets.UTF_8);
    }

    final var expected = Files.readAllLines(path, StandardCharsets.UTF_8);

    assertThat(actual).containsExactlyElementsOf(expected);
  }

  private enum Kind {
    PANEL,
    PANEL_PORT,
    PROTOCOL,
    OCI,
    CARGO
  }

  private static String answer(final String name, final Throwable ex, final Kind kind)
      throws Exception {

    final var request = new MockHttpServletRequest("GET", "/probe");
    final var response = new MockHttpServletResponse();

    switch (kind) {
      case PANEL -> {
        request.setServletPath("/api/probe");
        request.setAttribute(
            HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
            new HandlerMethod(new PanelProbe(), PanelProbe.class.getDeclaredMethod("probe")));
      }
      case PANEL_PORT -> {
        request.setServletPath("/api/unrouted");
        request.setLocalPort(API_PORT);
      }
      case PROTOCOL -> request.setServletPath("/npm/probe");
      case OCI -> request.setServletPath("/v2/repo/app/manifests/probe");
      case CARGO -> {
        request.setServletPath("/cargo/probe");
        request.setAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE, Boolean.TRUE);
      }
    }

    final var modelAndView = resolver.resolveException(request, response, null, (Exception) ex);

    final var status = modelAndView == null ? "unresolved" : Integer.toString(response.getStatus());
    final var headers = new TreeMap<String, List<String>>();

    for (final var header : response.getHeaderNames()) {
      headers.put(header, response.getHeaders(header));
    }

    final var body =
        UUID_PATTERN
            .matcher(response.getContentAsString(StandardCharsets.UTF_8))
            .replaceAll("<uuid>");

    // stripped: an empty body would leave trailing whitespace the editorconfig check rejects
    return "%s [%s] -> %s | %s | %s".formatted(name, kind, status, headers, body).stripTrailing();
  }

  @RestApiPort("api")
  static class PanelProbe {
    void probe() {
      // only its type annotation is read
    }
  }

  static class Probe {
    void m(final String parameter) {}

    @SuppressWarnings("unused")
    String field;

    @NotBlank String name;
  }

  private static MethodParameter parameter() {
    try {
      final Method method = Probe.class.getDeclaredMethod("m", String.class);
      return new MethodParameter(method, 0);
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Supplier<Throwable>> cases() throws Exception {
    final var cases = new TreeMap<String, Supplier<Throwable>>();

    // core exceptions of the shared handlers
    cases.put("AccessNotAllowed", () -> new AccessNotAllowedException("accessNotAllowed"));
    cases.put("AccessNotAllowedCustom", () -> new AccessNotAllowedException("customForbidden"));
    cases.put("AccessNotAllowedNoMessage", () -> new AccessNotAllowedException(null));
    cases.put("BadRequest", () -> new BadRequestException("badRequest"));
    cases.put("BadRequestNoMessage", () -> new BadRequestException(null));
    cases.put("ItemNotFound", () -> new ItemNotFoundException("itemNotFound"));
    cases.put("ItemNotFoundNoMessage", () -> new ItemNotFoundException(null));
    cases.put("ItemAlreadyExist", () -> new ItemAlreadyExistException("itemAlreadyExists"));
    cases.put("ItemAlreadyExistNoMessage", () -> new ItemAlreadyExistException(null));
    cases.put("SignatureNotVerified", () -> new SignatureNotVerifiedException("pgpFailed"));
    cases.put("SignatureNotVerifiedNoMessage", () -> new SignatureNotVerifiedException(null));
    cases.put("ErrorOccurred", () -> new ErrorOccurredException(new IllegalStateException("x")));
    cases.put("RedirectToPath", () -> new RedirectToPathException("/redirected"));
    cases.put("UnAuthorized", () -> new UnAuthorizedException("unAuthorized"));
    cases.put("UnAuthorizedCustom", () -> new UnAuthorizedException("tokenExpired"));
    cases.put("UnAuthorizedNoMessage", () -> new UnAuthorizedException(null));
    cases.put(
        "UnAuthorizedWithHeaders",
        () ->
            new UnAuthorizedException(
                "unAuthorized", Map.of("WWW-Authenticate", "Basic realm=\"repsy\"")));
    cases.put("Mfa", () -> new MfaException("mfaRequired"));
    cases.put("MfaNoMessage", () -> new MfaException(null));
    cases.put("Retryable", () -> new RetryableException("scanExecutorSaturated"));
    cases.put("TooManyRequests", () -> new TooManyRequestsException(30));

    // storage and persistence
    cases.put("InvalidStoragePath", () -> new InvalidStoragePathException("bad/../path"));
    cases.put(
        "StorageUnavailable", () -> new StorageUnavailableException("disk", new IOException("x")));
    cases.put(
        "OptimisticLockingFailure", () -> new OptimisticLockingFailureException("optimistic"));
    cases.put("OptimisticLock", () -> new OptimisticLockException("optimistic"));
    cases.put("PessimisticLockingFailure", () -> new CannotAcquireLockException("locked"));
    cases.put("PessimisticLock", () -> new PessimisticLockException("pessimistic"));
    cases.put("LockTimeout", () -> new LockTimeoutException("timeout"));
    cases.put(
        "ValueTooLong",
        () ->
            new DataIntegrityViolationException(
                "insert",
                new SQLException(
                    "ERROR: value too long for type character varying(255)", "22001")));
    cases.put(
        "ForeignKeyViolation",
        () ->
            new DataIntegrityViolationException(
                "insert", new SQLException("ERROR: foreign key", "23503")));

    // Spring web
    cases.put(
        "HttpMessageNotReadable",
        () ->
            new HttpMessageNotReadableException(
                "unreadable", new MockHttpInputMessage(new byte[0])));
    cases.put(
        "HttpRequestMethodNotSupported",
        () -> new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST")));
    cases.put(
        "HttpMediaTypeNotAcceptable",
        () -> new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));
    cases.put(
        "HttpMediaTypeNotSupported",
        () ->
            new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));
    cases.put(
        "NoResourceFound",
        () -> new NoResourceFoundException(HttpMethod.GET, "/api/nothing/here", "nothing/here"));
    cases.put(
        "MethodArgumentNotValid",
        () -> {
          final var result = new BeanPropertyBindingResult(new Probe(), "form");
          result.addError(
              new FieldError(
                  "form",
                  "name",
                  "",
                  false,
                  new String[] {"NotBlank.form.name", "NotBlank"},
                  null,
                  "must not be blank"));
          return new MethodArgumentNotValidException(parameter(), result);
        });
    cases.put(
        "HandlerMethodValidation",
        () -> {
          final var errors =
              List.of(new DefaultMessageSourceResolvable(new String[] {"Min"}, "must be >= 1"));
          final var result =
              new org.springframework.validation.method.ParameterValidationResult(
                  parameter(), 0, errors, null, null, null, (error, type) -> null);

          return new HandlerMethodValidationException(
              org.springframework.validation.method.MethodValidationResult.create(
                  new Probe(), parameter().getMethod(), List.of(result)));
        });
    cases.put(
        "UnsatisfiedServletRequestParameter",
        () ->
            new org.springframework.web.bind.UnsatisfiedServletRequestParameterException(
                new String[] {"a=1"}, java.util.Map.of()));
    cases.put("InvalidPagingParameter", () -> new InvalidPagingParameterException("page,size"));
    cases.put(
        "MissingServletRequestParameter",
        () -> new MissingServletRequestParameterException("q", "String"));
    cases.put(
        "MissingRequestHeaderAuthorization",
        () -> new MissingRequestHeaderException("Authorization", parameter()));
    cases.put(
        "MissingRequestHeaderOther",
        () -> new MissingRequestHeaderException("X-Probe", parameter()));
    cases.put("MissingRequestCookie", () -> new MissingRequestCookieException("sid", parameter()));
    cases.put("MissingMatrixVariable", () -> new MissingMatrixVariableException("m", parameter()));
    cases.put("MissingServletRequestPart", () -> new MissingServletRequestPartException("file"));
    cases.put(
        "MethodArgumentTypeMismatch",
        () ->
            new MethodArgumentTypeMismatchException(
                "nonsense", Integer.class, "type", parameter(), new IllegalArgumentException("x")));
    cases.put(
        "ConversionFailed",
        () ->
            new ConversionFailedException(
                TypeDescriptor.valueOf(String.class),
                TypeDescriptor.valueOf(Integer.class),
                "x",
                new IllegalArgumentException("x")));
    cases.put("MaxUploadSizeExceeded", () -> new MaxUploadSizeExceededException(1024));
    cases.put("Validation", () -> new ValidationException("invalid"));
    cases.put(
        "ConstraintViolation",
        () -> {
          final var probe = new Probe();
          probe.name = "";
          final var violations =
              Validation.buildDefaultValidatorFactory().getValidator().validate(probe);
          return new jakarta.validation.ConstraintViolationException("invalid", violations);
        });

    // the fallback
    cases.put("RuntimeFallback", () -> new IllegalStateException("boom"));
    cases.put(
        "HttpClientErrorFallback",
        () ->
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "bad gateway",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    return cases;
  }
}
