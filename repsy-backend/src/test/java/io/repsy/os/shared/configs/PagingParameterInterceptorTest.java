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
package io.repsy.os.shared.configs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.shared.error_handling.services.ErrorHandler;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives {@link PagingParameterInterceptor} together with Spring Data's real {@code Pageable}
 * resolver and {@link ErrorHandler}, so each case shows what a client actually receives. No Docker
 * or application context needed.
 */
class PagingParameterInterceptorTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new PagedController())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .addInterceptors(new PagingParameterInterceptor())
            .setControllerAdvice(new ErrorHandler(new RestResponseFactory(messageSource)))
            .build();
  }

  @Test
  @DisplayName("hands the endpoint's default page and size over when neither is sent")
  void defaultsWhenAbsent() throws Exception {
    this.mockMvc
        .perform(get("/paged"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:10"));
  }

  @ParameterizedTest(name = "page={0}, size={1}")
  @MethodSource("acceptedPaging")
  @DisplayName("passes valid, boundary and blank values through to the resolver")
  void acceptsValidPaging(final String page, final String size, final String expected)
      throws Exception {
    this.mockMvc
        .perform(get("/paged").param("page", page).param("size", size))
        .andExpect(status().isOk())
        .andExpect(content().string(expected));
  }

  static Stream<Arguments> acceptedPaging() {
    return Stream.of(
        Arguments.of("0", "1", "0:1"),
        Arguments.of("3", "100", "3:100"),
        // page * size == Integer.MAX_VALUE exactly: the product does not exceed it, so this is
        // still the boundary case for an individually valid, maximal page.
        Arguments.of("2147483647", "1", "2147483647:1"),
        // Large but non-overflowing: page * size stays well under Integer.MAX_VALUE (RPS-1150).
        Arguments.of("1000000", "100", "1000000:100"),
        // Just at the page*size==Integer.MAX_VALUE boundary for size=100.
        Arguments.of("21474836", "100", "21474836:100"),
        Arguments.of("", "", "0:10"),
        Arguments.of(" ", " ", "0:10"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("rejectedPaging")
  @DisplayName("answers 400 validationError naming the parameter for a bad page or size")
  void rejectsInvalidPaging(final String param, final String value) throws Exception {
    this.mockMvc
        .perform(get("/paged").param(param, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("validationError"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value(param));
  }

  static Stream<Arguments> rejectedPaging() {
    return Stream.of(
        Arguments.of("page", "abc"),
        Arguments.of("page", "-1"),
        Arguments.of("page", "2147483648"),
        Arguments.of("page", "1.5"),
        Arguments.of("page", " 2 "),
        Arguments.of("size", "abc"),
        Arguments.of("size", "0"),
        Arguments.of("size", "-1"),
        Arguments.of("size", "101"),
        Arguments.of("size", "2147483648"));
  }

  @ParameterizedTest(name = "page={0}, size={1}")
  @MethodSource("overflowingPaging")
  @DisplayName(
      "answers 400 validationError naming page when page * size overflows Integer.MAX_VALUE"
          + " (RPS-1150)")
  void rejectsOverflowingOffset(final String page, final String size) throws Exception {
    this.mockMvc
        .perform(get("/paged").param("page", page).param("size", size))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("validationError"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("page"));
  }

  static Stream<Arguments> overflowingPaging() {
    return Stream.of(
        // The exact reproduction from RPS-1150: both individually valid, product overflows.
        Arguments.of("2147483647", "100"),
        // Just one past the page*size==Integer.MAX_VALUE boundary for size=100.
        Arguments.of("21474837", "100"));
  }

  @Test
  @DisplayName("names both parameters when both are invalid")
  void namesBothParameters() throws Exception {
    this.mockMvc
        .perform(get("/paged").param("page", "-1").param("size", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("validationError"))
        .andExpect(jsonPath("$.data").value("page,size"));
  }

  @Test
  @DisplayName("leaves handlers without a Pageable alone, even if they receive a page or size")
  void ignoresHandlersWithoutPageable() throws Exception {
    this.mockMvc
        .perform(get("/unpaged").param("page", "abc").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  @RestController
  static class PagedController {

    @GetMapping("/paged")
    String paged(@PageableDefault final Pageable pageable) {
      return pageable.getPageNumber() + ":" + pageable.getPageSize();
    }

    @GetMapping("/unpaged")
    String unpaged() {
      return "ok";
    }
  }
}
