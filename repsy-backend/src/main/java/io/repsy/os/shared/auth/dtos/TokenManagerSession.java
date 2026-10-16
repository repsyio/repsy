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

import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.dtos.UserInfo;
import java.util.Set;

/**
 * The caller of a personal access token management route (RPS-2039): the user, and the scopes the
 * credential may hand on to a token it creates. A login may hand on every scope; a temporary token
 * only its own, which is what keeps a temporary token from minting a personal one that is wider.
 */
public record TokenManagerSession(UserInfo user, Set<TokenScope> grantable) {}
