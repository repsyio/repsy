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
package io.repsy.os.shared.user.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.UserCreateForm;
import io.repsy.os.generated.model.UserResponse;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
final class UserController {

  private final @NonNull UserTxService userTxService;
  private final @NonNull RestResponseFactory resp;

  @GetMapping
  public @NonNull RestResponse<PagedModel<UserResponse>> list(
      @RequestParam(required = false, defaultValue = "") final @NonNull String search,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "10") final int size) {

    final var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    final var usersPage = this.userTxService.getAllUsers(search, pageable);

    return this.resp.success("usersFetched", new PagedModel<>(usersPage));
  }

  @PostMapping
  public @NonNull RestResponse<UserResponse> createUser(
      @Valid @RequestBody final @NonNull UserCreateForm dto) {

    final var createdUser = this.userTxService.createUserWithRole(dto);

    return this.resp.success("userCreated", createdUser);
  }

  @PutMapping("/{userId}")
  public @NonNull RestResponse<UserResponse> updateUser(
      @PathVariable final @NonNull UUID userId,
      @Valid @RequestBody final @NonNull UserUpdateForm dto) {

    final var updatedUser = this.userTxService.updateUserDetails(userId, dto);

    return this.resp.success("userUpdated", updatedUser);
  }

  @DeleteMapping("/{userId}")
  public @NonNull RestResponse<Void> deleteUser(@PathVariable final @NonNull UUID userId) {

    this.userTxService.deleteUserById(userId);

    return this.resp.success("userDeleted");
  }

  @PostMapping("/{userId}/actions/reset-password")
  public @NonNull RestResponse<String> resetPassword(@PathVariable final @NonNull UUID userId) {

    final var newPassword = this.userTxService.resetUserPassword(userId);

    return this.resp.success("passwordReset", newPassword);
  }
}
