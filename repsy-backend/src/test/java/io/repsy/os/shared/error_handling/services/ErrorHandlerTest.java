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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.response.services.RestResponseFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Drives {@link ErrorHandler} through real Spring MVC argument resolution, so each test shows which
 * exception class MVC throws and which handler answers it. No Docker or application context needed.
 */
class ErrorHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ErrorHandler(new RestResponseFactory(messageSource)))
            .build();
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
  @DisplayName("answers 400 methodNotSupported for an unsupported HTTP method")
  void methodNotSupported() throws Exception {
    this.mockMvc
        .perform(multipart("/cookie"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("methodNotSupported"));
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

    @PostMapping("/part")
    String part(@RequestPart("file") final MultipartFile file) {
      return file.getName();
    }

    @GetMapping("/path-variable")
    String pathVariable(@PathVariable("id") final String id) {
      return id;
    }
  }
}
