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

import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.shared.error_handling.services.ErrorResponseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * When the response is already gone (a client that hung up), every handler of the two advices
 * writes nothing and answers {@code null}. Driven over all of their {@code @ExceptionHandler}
 * methods, so a new handler that forgets the guard fails here.
 */
@DisplayName("error advice handlers without a response")
class GoneResponseTest {

  private static final Class<?>[] ADVICES = {
    PanelProblemDetailAdvice.class, ProtocolErrorAdvice.class
  };

  private static final MethodParameter METHOD_PARAMETER = methodParameter();

  private static MethodParameter methodParameter() {
    try {
      return new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ParameterValidationResult failedParameter() {
    return new ParameterValidationResult(
        METHOD_PARAMETER,
        "gone",
        List.of(new DefaultMessageSourceResolvable("gone")),
        null,
        null,
        null,
        (error, type) -> null);
  }

  private static Object newAdvice(final Class<?> type) throws ReflectiveOperationException {
    final var errors =
        new ErrorResponseService(new RestResponseFactory(new ResourceBundleMessageSource()));

    return type.getDeclaredConstructor(ErrorResponseService.class).newInstance(errors);
  }

  static Stream<Method> handlers() {
    return Arrays.stream(ADVICES)
        .flatMap(advice -> Arrays.stream(advice.getDeclaredMethods()))
        .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
        .filter(
            method ->
                Arrays.asList(method.getParameterTypes()).contains(HttpServletResponse.class));
  }

  /** The simplest instance of an exception type: the first constructor that accepts defaults. */
  private static Throwable exceptionOf(final Class<?> type) throws ReflectiveOperationException {
    if (type == Throwable.class || type == Exception.class) {
      return new IllegalStateException("gone");
    }

    for (final Constructor<?> constructor : type.getConstructors()) {
      final var arguments =
          Arrays.stream(constructor.getParameterTypes()).map(GoneResponseTest::defaultOf).toArray();

      try {
        return (Throwable) constructor.newInstance(arguments);
      } catch (final InvocationTargetException | IllegalArgumentException ignored) {
        // try the next constructor
      }
    }

    throw new IllegalStateException("No usable constructor for " + type.getName());
  }

  private static Object defaultOf(final Class<?> type) {
    if (type == String.class) {
      return "gone";
    }
    if (type == long.class || type == int.class) {
      return 1;
    }
    if (type == Class.class) {
      return Object.class;
    }
    if (type == List.class) {
      return List.of();
    }
    if (type == Map.class) {
      return Map.of();
    }
    if (type == String[].class) {
      return new String[0];
    }
    if (type == MethodParameter.class) {
      return METHOD_PARAMETER;
    }
    if (type == BindingResult.class) {
      return new BeanPropertyBindingResult(new Object(), "gone");
    }
    if (type == MethodValidationResult.class) {
      try {
        return MethodValidationResult.create(
            new Object(), Object.class.getMethod("hashCode"), List.of(failedParameter()));
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException(e);
      }
    }
    return null;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("answers null and writes nothing")
  void answersNull(final Method handler) throws ReflectiveOperationException {
    handler.setAccessible(true);
    final var advice = newAdvice(handler.getDeclaringClass());
    final var arguments = new Object[handler.getParameterCount()];

    for (var i = 0; i < arguments.length; i++) {
      final var type = handler.getParameterTypes()[i];

      if (Throwable.class.isAssignableFrom(type)) {
        arguments[i] = exceptionOf(type);
      } else if (type == HttpServletRequest.class) {
        arguments[i] = new MockHttpServletRequest("GET", "/gone");
      }
    }

    assertThat(handler.invoke(advice, arguments)).isNull();
  }
}
