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
package io.repsy.os.server.protocols.docker.shared.storage.configs;

import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.repo.dtos.DefaultRepoDefinition;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default Docker repository a new user gets (RPS-2062). Docker's storage service is the one
 * that lives in this backend rather than in a protocol library, so the bean sits with it, and the
 * shared {@code DefaultRepoDefinitionsConfig} names no concrete protocol of this backend.
 */
@Configuration(proxyBeanMethods = false)
public class DockerDefaultRepoDefinitionConfig {

  @Bean
  public DefaultRepoDefinition dockerDefaultRepo(final DockerStorageService storage) {
    return new DefaultRepoDefinition("docker", RepoType.DOCKER, storage::createRepo);
  }
}
