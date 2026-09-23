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
package io.repsy.protocols.cargo.shared.crate.dtos;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Body of a {@code GET /api/v1/crates/{name}/owners} response. {@code cargo owner --list} models
 * this as a struct with a required {@code users} array, so the field must always be present, even
 * when empty.
 */
@NullMarked
public record CargoOwnersResponse(List<CargoOwnerUser> users) {}
