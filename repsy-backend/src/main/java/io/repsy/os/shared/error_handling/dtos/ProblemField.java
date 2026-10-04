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
package io.repsy.os.shared.error_handling.dtos;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the {@code errors} member of a panel problem (RFC 9457): a field, request parameter
 * or header the request failed validation on.
 *
 * @param field The name of the offending field, parameter or header
 * @param code The constraint that failed (for example {@code NotBlank} or {@code Min}), or the
 *     message id of the failure when no constraint is known
 * @param message A human readable description, or null
 */
@NullMarked
public record ProblemField(String field, String code, @Nullable String message) {}
