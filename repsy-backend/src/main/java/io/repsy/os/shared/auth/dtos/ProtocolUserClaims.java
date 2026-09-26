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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a verified protocol token says about the user it was issued to.
 *
 * @param username The user's name at the time of the login
 * @param tokenVersion The user's token version at the time of the login, or {@code null} for a
 *     token minted before the claim existed, which is accepted until it expires (RPS-1552)
 */
public record ProtocolUserClaims(@NonNull String username, @Nullable Integer tokenVersion) {}
