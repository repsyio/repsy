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
package io.repsy.os.shared.repo.events;

import java.util.UUID;

/**
 * A repo's {@code pgpVerifyAllSignaturesEnabled} setting was changed (RPS-1316). It says nothing of
 * the new value: whoever handles it reads the setting when it runs, so a handler that runs late,
 * twice or after another toggle still works from what the repo says now.
 *
 * <p>It is published inside the transaction that changed the setting and is only meant to be
 * handled after that commits ({@code @TransactionalEventListener}).
 *
 * @param repoId the repo whose setting was toggled
 */
public record PgpVerifyAllSignaturesToggledEvent(UUID repoId) {}
