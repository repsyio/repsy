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
package io.repsy.os.server.protocols.shared.configs;

import io.repsy.os.shared.repo.dtos.DefaultRepoDefinition;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default repository each protocol gets for a new user (RPS-2062). Every storage service is
 * taken as the protocol library's interface, the one place the other eight are reached the same
 * way; the Docker one is {@code DockerDefaultRepoDefinitionConfig}.
 */
@Configuration(proxyBeanMethods = false)
public class DefaultRepoDefinitionsConfig {

  @Bean
  public DefaultRepoDefinition cargoDefaultRepo(final CargoStorageService storage) {
    return new DefaultRepoDefinition("cargo", RepoType.CARGO, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition goDefaultRepo(final GoStorageService storage) {
    return new DefaultRepoDefinition("go", RepoType.GOLANG, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition helmDefaultRepo(final HelmStorageService storage) {
    return new DefaultRepoDefinition("helm", RepoType.HELM, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition mavenDefaultRepo(final MavenStorageService storage) {
    return new DefaultRepoDefinition("maven", RepoType.MAVEN, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition npmDefaultRepo(final NpmStorageService storage) {
    return new DefaultRepoDefinition("npm", RepoType.NPM, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition nugetDefaultRepo(final NuGetStorageService storage) {
    return new DefaultRepoDefinition("nuget", RepoType.NUGET, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition pypiDefaultRepo(final PypiStorageService storage) {
    return new DefaultRepoDefinition("pypi", RepoType.PYPI, storage::createRepo);
  }

  @Bean
  public DefaultRepoDefinition rubyDefaultRepo(final RubyStorageService storage) {
    return new DefaultRepoDefinition("ruby", RepoType.RUBY, storage::createRepo);
  }
}
