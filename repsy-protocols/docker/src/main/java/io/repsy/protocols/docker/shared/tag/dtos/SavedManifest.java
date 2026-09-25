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
package io.repsy.protocols.docker.shared.tag.dtos;

import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;

/**
 * What a manifest save answered with.
 *
 * @param digest the digest of the stored manifest
 * @param image the image it was saved into: the one the push found, or a new one when it was
 *     created (or created again after it was deleted) by the same transaction
 * @param <ID> the type of the ids
 */
public record SavedManifest<ID>(String digest, BaseImageInfo<ID> image) {}
