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
package io.repsy.protocols.helm.shared.oci.dtos;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import org.jspecify.annotations.NullMarked;

/**
 * The outcome of an OCI manifest push.
 *
 * @param manifest the manifest row as it was committed
 * @param usages the bytes the manifest file added. They are reported once the push has committed
 *     and not while it runs, because a push that lost a race is repeated and would charge its file
 *     twice.
 */
@NullMarked
public record HelmOciManifestPushResult(HelmOciManifestInfo manifest, BaseUsages usages) {}
