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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.UserCreateForm;
import io.repsy.os.generated.model.UserResponse;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.user.services.ReservedUsernameService;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
final class UserController {

  private static final Set<String> USER_SORT_PROPERTIES = Set.of("createdAt", "username");

  private final @NonNull PanelAuthHelper panelAuthHelper;
  private final @NonNull UserTxService userTxService;
  private final @NonNull ReservedUsernameService reservedUsernameService;
  private final @NonNull RestResponseFactory resp;

  @GetMapping
  public @NonNull RestResponse<PagedModel<UserResponse>> list(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestParam(name = "q", required = false, defaultValue = "") final @NonNull String search,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    SortValidator.requireSortableBy(pageable, USER_SORT_PROPERTIES);

    final var usersPage = this.userTxService.getAllUsers(search, pageable);

    return this.resp.success("usersFetched", new PagedModel<>(usersPage));
  }

  /**
   * The number of admins, whatever page or search the users list is showing (RPS-1246): the panel
   * uses it to tell whether the admin it is about to delete or demote is the last one.
   */
  @GetMapping("/admin-count")
  public @NonNull RestResponse<Long> countAdmins(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    return this.resp.success("adminCountFetched", this.userTxService.countAdmins());
  }

  @PostMapping
  public @NonNull RestResponse<UserResponse> createUser(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @Valid @RequestBody final @NonNull UserCreateForm dto) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    this.reservedUsernameService.requireNotReserved(dto.getUsername());

    final var createdUser = this.userTxService.createUserWithRole(dto);

    return this.resp.success("userCreated", createdUser);
  }

  @PutMapping("/{userId}")
  public @NonNull RestResponse<UserResponse> updateUser(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull UUID userId,
      @Valid @RequestBody final @NonNull UserUpdateForm dto) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    // A user who already holds a now-reserved name may keep it; only a rename is checked.
    if (!this.userTxService.getUserById(userId).getUsername().equals(dto.getUsername())) {
      this.reservedUsernameService.requireNotReserved(dto.getUsername());
    }

    final var updatedUser = this.userTxService.updateUserDetails(userId, dto);

    return this.resp.success("userUpdated", updatedUser);
  }

  @DeleteMapping("/{userId}")
  public @NonNull RestResponse<Void> deleteUser(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull UUID userId) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    this.userTxService.deleteUserById(userId);

    return this.resp.success("userDeleted");
  }

  @PostMapping("/{userId}/actions/reset-password")
  public @NonNull RestResponse<String> resetPassword(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull UUID userId) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    final var newPassword = this.userTxService.resetUserPassword(userId);

    return this.resp.success("passwordReset", newPassword);
  }
}
