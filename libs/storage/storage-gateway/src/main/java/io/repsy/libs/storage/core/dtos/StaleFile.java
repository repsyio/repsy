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
package io.repsy.libs.storage.core.dtos;

import org.jspecify.annotations.NonNull;

/**
 * A regular file that has not been written to for a while, as answered by {@code
 * StorageStrategy.listStaleFiles}.
 *
 * @param name the file name, without its directory
 * @param size the file size in bytes
 */
public record StaleFile(@NonNull String name, long size) {}
