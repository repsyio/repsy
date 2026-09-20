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
package io.repsy.os.server.protocols.nuget.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetPackageServiceImpl;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Full-stack coverage for the NuGet V3 read endpoints on the protocol port: the package version
 * list ({@code /v3/package/{id}/index.json}) and the {@code .nupkg} / {@code .nuspec} downloads.
 *
 * <p>Both handlers used to answer 404 for every exception, so a database or storage failure looked
 * like a missing package to {@code nuget restore} and left nothing in the log (RPS-997). They now
 * answer 404 only for a package, version or file that does not exist, and 500 plus an error log
 * with the stack trace for anything else. The successful reads are covered by {@link
 * NuGetPublishProtocolIT}, which pushes real packages first.
 */
@DisplayName("NuGet wire protocol read endpoints")
@ExtendWith(OutputCaptureExtension.class)
class NuGetReadProtocolIT extends AbstractIntegrationTest {

  private static final String PACKAGE_ID = "repsy.missing";
  private static final String VERSIONS_PATH = "/{repo}/v3/package/{id}/index.json";
  private static final String NUPKG_PATH = "/{repo}/v3/package/{id}/1.0.0/{id}.1.0.0.nupkg";
  private static final String NUSPEC_PATH = "/{repo}/v3/package/{id}/1.0.0/{id}.1.0.0.nuspec";

  @MockitoSpyBean private NuGetStorageService nugetStorageService;
  @MockitoSpyBean private NuGetPackageServiceImpl nugetPackageService;

  private Repo repo;

  @BeforeEach
  void seedNuGetRepo() {
    this.repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-read"));
  }

  private ResultActions read(final String template) throws Exception {
    final AbstractMockHttpServletRequestBuilder<?> request =
        get(template, this.repo.getName(), PACKAGE_ID, PACKAGE_ID)
            .header(AUTHORIZATION, this.adminProtocolBearerToken());

    return this.mockMvc.perform(request.with(protocolPort()));
  }

  @Nested
  @DisplayName("a package, version or file that does not exist")
  class NotFound {

    @Test
    @DisplayName("answers 404 for the version list of an unknown package")
    void versionList(final CapturedOutput output) throws Exception {
      NuGetReadProtocolIT.this.read(VERSIONS_PATH).andExpect(status().isNotFound());

      assertThat(output.getAll()).doesNotContain("NuGet package versions failed");
    }

    @Test
    @DisplayName("answers 404 for the .nupkg of an unknown package")
    void nupkg(final CapturedOutput output) throws Exception {
      NuGetReadProtocolIT.this.read(NUPKG_PATH).andExpect(status().isNotFound());

      assertThat(output.getAll()).doesNotContain("NuGet download failed");
    }

    @Test
    @DisplayName("answers 404 for the .nuspec of an unknown package")
    void nuspec(final CapturedOutput output) throws Exception {
      NuGetReadProtocolIT.this.read(NUSPEC_PATH).andExpect(status().isNotFound());

      assertThat(output.getAll()).doesNotContain("NuGet download failed");
    }
  }

  @Nested
  @DisplayName("an unexpected failure")
  class UnexpectedFailure {

    @Test
    @DisplayName("answers 500 for the version list and logs the exception with its stack trace")
    void versionList(final CapturedOutput output) throws Exception {
      doThrow(new IllegalStateException("database down"))
          .when(NuGetReadProtocolIT.this.nugetPackageService)
          .getAllVersionInfos(any(), any());

      NuGetReadProtocolIT.this.read(VERSIONS_PATH).andExpect(status().isInternalServerError());

      assertThat(output.getAll())
          .contains("ERROR")
          .contains("NuGet package versions failed")
          .contains("java.lang.IllegalStateException: database down");
    }

    @Test
    @DisplayName("answers 500 for the .nupkg download and logs the exception with its stack trace")
    void nupkg(final CapturedOutput output) throws Exception {
      doThrow(new IllegalStateException("storage backend down"))
          .when(NuGetReadProtocolIT.this.nugetStorageService)
          .getNuPkg(any(), any(), any());

      NuGetReadProtocolIT.this.read(NUPKG_PATH).andExpect(status().isInternalServerError());

      assertThat(output.getAll())
          .contains("ERROR")
          .contains("NuGet download failed")
          .contains("java.lang.IllegalStateException: storage backend down");
    }

    @Test
    @DisplayName("answers 500 for the .nuspec download and logs the exception with its stack trace")
    void nuspec(final CapturedOutput output) throws Exception {
      doThrow(new IllegalStateException("storage backend down"))
          .when(NuGetReadProtocolIT.this.nugetStorageService)
          .getNuspec(any(), any(), any());

      NuGetReadProtocolIT.this.read(NUSPEC_PATH).andExpect(status().isInternalServerError());

      assertThat(output.getAll())
          .contains("ERROR")
          .contains("NuGet download failed")
          .contains("java.lang.IllegalStateException: storage backend down");
    }
  }
}
