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
package io.repsy.os.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.docker.shared.tag.tasks.DockerManifestLayoutRepairTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The upgrade progress the README promises is logged at INFO under the default root level WARN. */
class UpgradeProgressLoggingTest {

  @Test
  void theDockerManifestLayoutRepairReportIsLoggedAtInfo() {

    final var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    final var properties = yaml.getObject();

    assertThat(properties).isNotNull();
    assertThat(properties.getProperty("logging.level.root")).isEqualTo("WARN");
    assertThat(
            properties.getProperty(
                "logging.level." + DockerManifestLayoutRepairTask.class.getPackageName()))
        .as("the package of the task that logs the repair report")
        .isEqualTo("INFO");
  }
}
