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
 * Where a Maven repo looks for the public keys of its signers was changed (RPS-1334): a public key
 * or an allowed key-server host was registered on it or deleted, or its {@code
 * pgpKeyServerLookupEnabled} setting was toggled. What a stored {@code .asc} verifies against is
 * then not what it was, so the repo's {@code signed} is recomputed, but only when the repo verifies
 * every signature (a repo that does not counts the signature of its POM, which was verified when it
 * was uploaded and is not verified again by a change of the key sources).
 *
 * <p>It says nothing of the new sources nor of the repo's {@code pgpVerifyAllSignaturesEnabled}
 * setting: whoever handles it reads both when it runs, like {@link
 * PgpVerifyAllSignaturesToggledEvent}. It is a separate event and not that one renamed, because
 * that one is published for a change that concerns the repo whichever way it went, this one is only
 * of interest to a repo that verifies every signature.
 *
 * <p>It is published inside the transaction that changed the sources and is only meant to be
 * handled after that commits ({@code @TransactionalEventListener}).
 *
 * @param repoId the repo whose key sources changed
 */
public record PgpKeySourcesChangedEvent(UUID repoId) {}
