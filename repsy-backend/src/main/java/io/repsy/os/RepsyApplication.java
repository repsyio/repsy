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

import io.repsy.libs.multiport.annotations.EnableMultiport;
import io.repsy.os.server.protocols.docker.shared.storage.configs.DockerFileSystemStorageBackendConfigProps;
import io.repsy.os.server.protocols.golang.shared.storage.configs.GolangFileSystemStorageBackendConfigProps;
import io.repsy.os.server.protocols.maven.shared.storage.configs.MavenFileSystemStorageBackendConfigProps;
import io.repsy.os.server.protocols.npm.shared.storage.configs.NpmFileSystemStorageBackendConfigProps;
import io.repsy.os.server.protocols.pypi.shared.storage.configs.PypiFileSystemStorageBackendConfigProps;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Slf4j
@EnableConfigurationProperties({
  MavenFileSystemStorageBackendConfigProps.class,
  NpmFileSystemStorageBackendConfigProps.class,
  PypiFileSystemStorageBackendConfigProps.class,
  DockerFileSystemStorageBackendConfigProps.class,
  GolangFileSystemStorageBackendConfigProps.class
})
@SpringBootApplication(
    scanBasePackages = {"io.repsy.os", "io.repsy.core", "io.repsy.libs", "io.repsy.protocols"})
@EnableMultiport
public class RepsyApplication {

  @PostConstruct
  private void init() {
    log.warn("repsy started successfully!");
  }

  static void main(final String[] args) {
    SpringApplication.run(RepsyApplication.class, args);
  }
}
