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
package io.repsy.os.server.protocols.maven.shared.keystore.dtos;

import org.jspecify.annotations.Nullable;

/**
 * The identity fields read off the primary key of a parsed armored OpenPGP public key block
 * (RPS-1189).
 *
 * @param keyIdHex the primary key's 64 bit key id, upper-case hex, 16 characters
 * @param fingerprintHex the primary key's fingerprint, upper-case hex
 * @param userId the first user id packet of the primary key, or {@code null} when it has none
 */
public record ParsedPublicKey(String keyIdHex, String fingerprintHex, @Nullable String userId) {}
