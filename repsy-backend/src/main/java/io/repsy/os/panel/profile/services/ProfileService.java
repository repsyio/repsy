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
package io.repsy.os.panel.profile.services;

import io.repsy.os.generated.model.ProfileInfo;
import io.repsy.os.generated.model.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProfileService {

  private final @NonNull UserTxService userTxService;

  public ProfileInfo getProfile(final @NonNull UUID userId) {
    final var user = this.userTxService.getUserById(userId);

    return ProfileInfo.builder()
        .id(user.getId())
        .username(user.getUsername())
        .role(UserRole.valueOf(user.getRole().name()))
        .createdAt(user.getCreatedAt())
        .lastLoginAt(user.getLastLoginAt())
        .build();
  }
}
