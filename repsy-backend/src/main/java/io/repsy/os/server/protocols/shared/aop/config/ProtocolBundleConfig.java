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
package io.repsy.os.server.protocols.shared.aop.config;

import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@NullMarked
@Configuration
public class ProtocolBundleConfig {

  @Bean
  public Map<RepoType, ProtocolAuthService> protocolAuthServiceMap(
      @Qualifier("cargoAuthComponent") final ProtocolAuthService cargo,
      @Qualifier("mavenAuthComponent") final ProtocolAuthService maven,
      @Qualifier("npmAuthComponentImpl") final ProtocolAuthService npm,
      @Qualifier("dockerAuthComponent") final ProtocolAuthService docker,
      @Qualifier("pypiAuthComponent") final ProtocolAuthService pypi,
      @Qualifier("golangAuthComponent") final ProtocolAuthService golang) {

    return Map.of(
        RepoType.CARGO, cargo,
        RepoType.MAVEN, maven,
        RepoType.NPM, npm,
        RepoType.DOCKER, docker,
        RepoType.PYPI, pypi,
        RepoType.GOLANG, golang);
  }

  @Bean
  public Map<RepoType, ProtocolApiFacade> protocolApiFacadeMap(
      @Qualifier("cargoApiFacade") final ProtocolApiFacade cargo,
      @Qualifier("mavenApiFacade") final ProtocolApiFacade maven,
      @Qualifier("npmApiFacade") final ProtocolApiFacade npm,
      @Qualifier("dockerApiFacade") final ProtocolApiFacade docker,
      @Qualifier("pypiApiFacade") final ProtocolApiFacade pypi,
      @Qualifier("golangApiFacade") final ProtocolApiFacade golang) {

    return Map.of(
        RepoType.CARGO, cargo,
        RepoType.MAVEN, maven,
        RepoType.NPM, npm,
        RepoType.DOCKER, docker,
        RepoType.PYPI, pypi,
        RepoType.GOLANG, golang);
  }
}
