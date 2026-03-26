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
package io.repsy.os.shared.user.mappers;

import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.dtos.UserResponse;
import io.repsy.os.shared.user.entities.User;
import org.jspecify.annotations.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Mappings;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserConverter {

  @NonNull UserInfo toUserInfo(@NonNull User user);

  @Mappings(@Mapping(target = "id", expression = "java(user.getId().toString())"))
  @NonNull UserResponse toUserResponseDto(@NonNull User user);
}
