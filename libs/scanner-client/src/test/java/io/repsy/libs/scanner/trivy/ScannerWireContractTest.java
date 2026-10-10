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
package io.repsy.libs.scanner.trivy;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.scanner.dtos.FixStatus;
import io.repsy.libs.scanner.dtos.ScanOutcome;
import io.repsy.libs.scanner.dtos.ScannerFinding;
import io.repsy.libs.scanner.dtos.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON the backend exchanges with {@code repsy-scanner-trivy}: field names and value
 * shapes of the wire records. {@code e2e/src/stubs/scanner/contract.ts} holds the same contract on
 * the stub side; a rename of a record component fails here before it fails there.
 */
class ScannerWireContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void advisoryLookupRequestIsEcosystemAndPackages() throws Exception {
    final var request =
        new AdvisoryLookupRequest("npm", List.of(new AdvisoryPackage("lodash", "4.17.20")));

    assertThat(MAPPER.readTree(MAPPER.writeValueAsString(request)))
        .isEqualTo(
            MAPPER.readTree(
                "{\"ecosystem\":\"npm\",\"packages\":[{\"name\":\"lodash\",\"version\":\"4.17.20\"}]}"));
  }

  @Test
  void advisoryLookupResponseReadsDatabaseTimeScannerVersionAndFindings() throws Exception {
    final var json =
        """
        {"dbUpdatedAt":"2026-01-02T03:04:05Z","scannerVersion":"0.66.0","findings":[
          {"cveId":"CVE-1","severity":"HIGH","packageName":"lodash","packageVersion":"4.17.20",
            "fixedVersion":"4.17.21","description":"d","referenceUrl":"https://x",
            "fixStatus":"FIXED","cvssScore":7.5,"cvssVector":"AV:N"}]}
        """;

    final var response = MAPPER.readValue(json, AdvisoryLookupResponse.class);

    assertThat(response.dbUpdatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    assertThat(response.scannerVersion()).isEqualTo("0.66.0");
    assertThat(response.findings())
        .containsExactly(
            new ScannerFinding(
                "CVE-1",
                Severity.HIGH,
                "lodash",
                "4.17.20",
                "4.17.21",
                "d",
                "https://x",
                FixStatus.FIXED,
                7.5,
                "AV:N"));
  }

  @Test
  void scanJobStatusResponseReadsScanIdStatusResultAndErrorMessage() throws Exception {
    final var scanId = UUID.randomUUID();
    final var json =
        "{\"scanId\":\""
            + scanId
            + "\",\"status\":\"COMPLETED\","
            + "\"result\":{\"findings\":[],\"scannerVersion\":\"0.66.0\"},\"errorMessage\":null}";

    final var response = MAPPER.readValue(json, ScanJobStatusResponse.class);

    assertThat(response.scanId()).isEqualTo(scanId);
    assertThat(response.status()).isEqualTo(ScanJobStatus.COMPLETED);
    assertThat(response.result()).isEqualTo(new ScanOutcome(List.of(), "0.66.0"));
    assertThat(response.errorMessage()).isNull();
  }

  @Test
  void scanJobStatusNamesAreTheScannersStatusNames() {
    assertThat(ScanJobStatus.values())
        .extracting(Enum::name)
        .containsExactly("QUEUED", "RUNNING", "COMPLETED", "FAILED");
  }

  @Test
  void scannerSupportsTheFiveRepoTypesIncludingHelm() {
    final var properties =
        new TrivyScannerClientProperties(
            "http://scanner",
            "key",
            10,
            3000,
            330,
            3,
            15,
            60,
            TrivyScannerClientProperties.DEFAULT_SUPPORTED_REPO_TYPES);
    final var scanner =
        new TrivyVulnerabilityScanner(
            properties,
            new DockerRegistryProperties("http://localhost:9090"),
            new TrivyScannerRestClientConfig().trivyScannerUploadRestClient(properties));

    assertThat(scanner.getSupportedRepoTypes())
        .isEqualTo(Set.of("MAVEN", "NPM", "PYPI", "DOCKER", "HELM"));
  }
}
