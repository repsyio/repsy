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
 * What a verified protocol token says.
 *
 * @param subject The user id, or the deploy token id for a {@link AuthenticationType#DEPLOY_TOKEN}
 * @param authenticationType How the token was obtained
 * @param expiresAt When the token stops working on its own
 */
public record ProtocolTokenClaims(
    @NonNull UUID subject,
    @NonNull AuthenticationType authenticationType,
    @NonNull Instant expiresAt) {}
