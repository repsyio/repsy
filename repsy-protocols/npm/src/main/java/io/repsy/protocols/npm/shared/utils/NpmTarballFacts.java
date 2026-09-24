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
package io.repsy.protocols.npm.shared.utils;

import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * What a tarball vouches for: the manifest in its own {@code package.json} and its digests.
 *
 * @param manifest the parsed {@code package.json} inside the tarball, empty when there is none or
 *     it cannot be read
 * @param shasum the SHA-1 of the tarball, in hex
 * @param integrity the SHA-512 of the tarball as an SRI string ({@code sha512-<base64>})
 */
@NullMarked
public record NpmTarballFacts(Map<String, Object> manifest, String shasum, String integrity) {}
