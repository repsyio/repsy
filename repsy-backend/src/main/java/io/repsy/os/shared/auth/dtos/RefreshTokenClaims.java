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
package io.repsy.os.shared.auth.dtos;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The claims of a verified refresh token that decide whether it may be exchanged.
 *
 * @param userId the user the token was issued to
 * @param tokenId the unique identifier of this refresh token
 * @param familyId the identifier shared by all tokens descended from one login
 * @param sessionStart when the login this token descends from happened; carried unchanged across
 *     refreshes so the session has an absolute lifetime
 * @param tokenVersion the user's {@code token_version} when the token was issued; a later change of
 *     it revokes the token
 */
public record RefreshTokenClaims(
    @NonNull UUID userId,
    @NonNull UUID tokenId,
    @NonNull UUID familyId,
    @NonNull Instant sessionStart,
    int tokenVersion) {}
