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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.response.services.RestResponseFactory;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RPS-1039: {@link OciErrorBodyAdvice} rewrites the error {@link ErrorHandler} renders into the OCI
 * distribution body on {@code /v2/} requests only, and leaves status, headers and every other body
 * alone. Runs through real Spring MVC, with no Docker or application context.
 */
@DisplayName("OciErrorBodyAdvice")
class OciErrorBodyAdviceTest {

  private MockMvc mockMvc;

  /** The protocol router puts the request path in the servlet path, so the tests do too. */
  private static MockHttpServletRequestBuilder protocol(final String path) {
    return get(path).servletPath(path);
  }

  @BeforeEach
  void setUp() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(
                new ErrorHandler(new RestResponseFactory(messageSource)), new OciErrorBodyAdvice())
            .build();
  }

  @Test
  @DisplayName("answers a failure on /v2/ with the distribution body and keeps the status")
  void rewritesOciFailure() throws Exception {
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_INVALID"))
        .andExpect(
            jsonPath("$.errors[0].message").value("The manifest is not a valid JSON object."))
        .andExpect(jsonPath("$.errors[0].detail").value("manifestInvalidJson"))
        .andExpect(jsonPath("$.msgId").doesNotExist())
        .andExpect(jsonPath("$.errorCode").doesNotExist());
  }

  @Test
  @DisplayName("uses the code of each kind of failure")
  void mapsEachFailure() throws Exception {
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/exists"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("DENIED"));
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("DENIED"));
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/crash"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
  }

  @Test
  @DisplayName("answers a lost version race with 503, Retry-After and the distribution body")
  void lostVersionRaceIsARetryableRegistryError() throws Exception {
    this.mockMvc
        .perform(protocol("/v2/repo/app/manifests/contended"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"))
        .andExpect(jsonPath("$.errors[0].detail").value("concurrentModification"))
        .andExpect(jsonPath("$.msgId").doesNotExist());
  }

  @Test
  @DisplayName("keeps the content type JSON")
  void keepsContentType() throws Exception {
    final var result =
        this.mockMvc.perform(protocol("/v2/repo/app/manifests/bad")).andReturn().getResponse();

    assertThat(result.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  @DisplayName("leaves the panel envelope of every other path alone")
  void keepsEnvelopeOutsideOci() throws Exception {
    this.mockMvc
        .perform(protocol("/api/other"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("manifestInvalidJson"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("does not touch a successful response on /v2/")
  void keepsSuccess() throws Exception {
    this.mockMvc
        .perform(protocol("/v2/repo/app/ok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(true))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @RestController
  static class FailingController {

    @GetMapping("/v2/repo/app/manifests/bad")
    String bad() {
      throw new BadRequestException("manifestInvalidJson");
    }

    @GetMapping("/v2/repo/app/manifests/missing")
    String missing() {
      throw new ItemNotFoundException("manifestNotFound");
    }

    @GetMapping("/v2/repo/app/manifests/exists")
    String exists() {
      throw new ItemAlreadyExistException("chartAlreadyExists");
    }

    @GetMapping("/v2/repo/app/manifests/denied")
    String denied() {
      throw new AccessNotAllowedException("packageOverrideDisabled");
    }

    @GetMapping("/v2/repo/app/manifests/contended")
    String contended() {
      throw new ObjectOptimisticLockingFailureException(Object.class, "id");
    }

    @GetMapping("/v2/repo/app/manifests/crash")
    String crash() {
      throw new IllegalStateException("boom");
    }

    @GetMapping("/api/other")
    String other() {
      throw new BadRequestException("manifestInvalidJson");
    }

    @GetMapping("/v2/repo/app/ok")
    Map<String, Boolean> ok() {
      return Map.of("ready", true);
    }
  }
}
