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
package io.repsy.os.server.security.scanner;

import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NonNull;

/**
 * A scannable artifact's bytes, decoupled from where they physically live (storage-backed resource,
 * temp file, etc). {@link #openStream()} may be called at most once per scan attempt by a given
 * caller — implementations are not required to support concurrent reads, only repeated sequential
 * opens (e.g. across retries).
 */
public interface ArtifactContent {

  @NonNull String fileName();

  @NonNull InputStream openStream() throws IOException;
}
