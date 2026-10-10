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
package io.repsy.protocols.shared.auth;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What {@link VerifiedPasswordCache} needs to know about the stored password of a user: the
 * username and the stored hash and salt. Each product's user DTO implements it, so the cache does
 * not depend on any user model.
 */
@NullMarked
public interface StoredPasswordCredentials {

  String getUsername();

  @Nullable String getHash();

  @Nullable String getSalt();
}
