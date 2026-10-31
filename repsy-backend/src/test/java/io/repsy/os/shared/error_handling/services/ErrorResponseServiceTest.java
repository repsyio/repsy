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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.core.web_error.ProblemField;
import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.MapBindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@DisplayName("ErrorResponseService")
class ErrorResponseServiceTest {

  private static final int API_PORT = 8080;
  private static final int MAIN_PORT = 9090;

  private static final RestResponseFactory RESPONSES =
      new RestResponseFactory(new ResourceBundleMessageSource());

  @SuppressWarnings("unused")
  void handler(final String form, final String header, final String other) {}

  private static MethodParameter parameter(final int index) throws NoSuchMethodException {
    final Method method =
        ErrorResponseServiceTest.class.getDeclaredMethod(
            "handler", String.class, String.class, String.class);
    final var parameter = new MethodParameter(method, index);
    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());

    return parameter;
  }

  private static List<ProblemField> fieldsOf(final ParameterValidationResult... results)
      throws NoSuchMethodException {

    final var method =
        ErrorResponseServiceTest.class.getDeclaredMethod(
            "handler", String.class, String.class, String.class);
    final var validation =
        MethodValidationResult.create(new ErrorResponseServiceTest(), method, List.of(results));

    return ErrorResponseService.fieldsOf(new HandlerMethodValidationException(validation));
  }

  private static ParameterValidationResult resolvable(
      final MethodParameter parameter, final MessageSourceResolvable... errors) {

    return new ParameterValidationResult(
        parameter, "value", List.of(errors), null, null, null, (error, type) -> null);
  }

  private static MockHttpServletRequest requestOnPort(final int port) {
    final var request = new MockHttpServletRequest();
    request.setLocalPort(port);

    return request;
  }

  private static ErrorResponseService withPorts(
      final Map<String, Integer> ports, final Map<Integer, String> aliases) {

    final var props = new MultiPortProperties();
    props.setPorts(ports);
    props.setPortAliases(aliases);

    return new ErrorResponseService(RESPONSES, props);
  }

  @Test
  @DisplayName("names the fields of a validated bean argument by their constraint")
  void beanArgumentFields() throws NoSuchMethodException {
    final var errors = new MapBindingResult(Map.of("name", ""), "form");
    errors.rejectValue("name", "NotBlank", "must not be blank");
    final var result = new ParameterErrors(parameter(0), "value", errors, null, null, null);

    assertThat(fieldsOf(result))
        .containsExactly(new ProblemField("name", "NotBlank", "must not be blank"));
  }

  @Test
  @DisplayName("takes the most general code of a constraint, or validationError without codes")
  void constraintCodes() throws NoSuchMethodException {
    final var sized =
        new DefaultMessageSourceResolvable(new String[] {"Size.other", "Size"}, "too long");
    final var codeless = new DefaultMessageSourceResolvable(null, "odd");

    assertThat(fieldsOf(resolvable(parameter(2), sized, codeless)))
        .containsExactly(
            new ProblemField("other", "Size", "too long"),
            new ProblemField("other", ProtocolErrorCodes.VALIDATION_ERROR, "odd"));
  }

  @Test
  @DisplayName("is no panel request without port configuration")
  void noPortConfiguration() {
    assertThat(new ErrorResponseService(RESPONSES).isPanelRequest(requestOnPort(API_PORT)))
        .isFalse();
    assertThat(withPorts(null, Map.of()).isPanelRequest(requestOnPort(API_PORT))).isFalse();
  }

  @Test
  @DisplayName("is a panel request on the api port or one of its aliases")
  void portAndAlias() {
    final var service = withPorts(Map.of("api", API_PORT), Map.of(8443, "api", 9443, "main"));

    assertThat(service.isPanelRequest(requestOnPort(API_PORT))).isTrue();
    assertThat(service.isPanelRequest(requestOnPort(8443))).isTrue();
    assertThat(service.isPanelRequest(requestOnPort(9443))).isFalse();
    assertThat(service.isPanelRequest(requestOnPort(MAIN_PORT))).isFalse();
  }

  @Test
  @DisplayName("is no panel request when no api port is configured")
  void noApiPort() {
    final var service = withPorts(Map.of("main", MAIN_PORT), Map.of());

    assertThat(service.isPanelRequest(requestOnPort(API_PORT))).isFalse();
  }
}
