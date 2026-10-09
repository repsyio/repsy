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
package io.repsy.os.shared.token.mappers;

import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.AccessTokenWhoAmI;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@NullMarked
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PersonalAccessTokenMapper {

  /**
   * Reads the user of the token, so call it inside the transaction that loaded the token: the user
   * is a lazy association (the repository fetches it together with the token where it can).
   */
  @Mapping(target = "userId", source = "user.id")
  @Mapping(target = "username", source = "user.username")
  @Mapping(target = "scopes", source = "scopes")
  PersonalAccessTokenInfo toInfo(PersonalAccessToken token);

  AccessTokenWhoAmI toWhoAmI(PersonalAccessTokenInfo info);

  /** The response of a create: the metadata of the token and, once, its secret. */
  @Mapping(target = "id", source = "info.id")
  @Mapping(target = "name", source = "info.name")
  @Mapping(target = "scopes", source = "info.scopes")
  @Mapping(target = "expirationDate", source = "info.expirationDate")
  @Mapping(target = "createdAt", source = "info.createdAt")
  @Mapping(target = "token", source = "token")
  AccessTokenCreated toCreated(PersonalAccessTokenInfo info, String token);
}
