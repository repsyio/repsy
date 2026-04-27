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
package io.repsy.os.server.shared.token.mappers;

import io.repsy.os.generated.model.DeployTokenForm;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public interface DeployTokenConverter {
  @Mappings({
    @Mapping(target = "id", ignore = true),
    @Mapping(target = "repo", ignore = true),
    @Mapping(target = "lastUsedAt", ignore = true),
    @Mapping(target = "createdAt", ignore = true),
    @Mapping(target = "tokenDurationDay", ignore = true),
    @Mapping(target = "token", ignore = true),
    @Mapping(
        target = "readOnly",
        expression = "java(Boolean.TRUE.equals(deployTokenForm.getReadOnly()))"),
    @Mapping(target = "expirationDate", source = "expirationDate")
  })
  RepoDeployToken toDeployToken(DeployTokenForm deployTokenForm);

  @Mapping(target = "readOnly", source = "readOnly")
  DeployTokenInfo toDeployTokenInfo(RepoDeployToken repoDeployToken);
}
