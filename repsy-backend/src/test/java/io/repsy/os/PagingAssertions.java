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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What every paged list endpoint answers to an unacceptable {@code page}, {@code size} or {@code
 * sort}, shared by the protocol integration tests so each one pins the same rule.
 */
public final class PagingAssertions {

  /** A sort property no list endpoint has. */
  public static final String UNKNOWN_SORT = "bogus,asc";

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private PagingAssertions() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** The {@code page}/{@code size} values every paged endpoint rejects, as (name, value) pairs. */
  public static Stream<Arguments> invalidPagingParams() {
    return Stream.of(
        Arguments.of("page", "abc"),
        Arguments.of("page", "-1"),
        Arguments.of("size", "abc"),
        Arguments.of("size", "0"),
        Arguments.of("size", "-1"),
        Arguments.of("size", "101"));
  }

  /**
   * Asserts the 400 {@code validationError} envelope that names the offending request parameter.
   *
   * @param result Response to check
   * @param parameter Parameter the response has to name: {@code page}, {@code size} or {@code sort}
   */
  public static void expectInvalidParameter(final ResultActions result, final String parameter)
      throws Exception {

    final var body =
        result
            .andExpect(status().is(HttpStatus.BAD_REQUEST.value()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    final Map<String, Object> envelope = JsonPath.read(body, "$");

    assertThat(envelope)
        .containsOnlyKeys("msgId", "type", "data", "errorCode", "text")
        .containsEntry("msgId", "validationError")
        .containsEntry("type", "ERROR")
        .containsEntry("data", parameter)
        .containsEntry("text", "Incoming data couldn't be validated.");
    assertThat((String) envelope.get("errorCode")).matches(UUID_PATTERN);
  }
}
