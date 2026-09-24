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
package io.repsy.protocols.docker.shared.image.exceptions;

import org.jspecify.annotations.NullMarked;

/**
 * The image a manifest push looked up was deleted before the push could write to it: an image goes
 * with its last manifest (RPS-1288). Not an error for the client: the push handler creates the
 * image again and runs the save once more.
 */
@NullMarked
public class ImageDeletedException extends RuntimeException {

  public ImageDeletedException(final Object imageId) {
    super("The image " + imageId + " was deleted while a manifest was being pushed into it");
  }
}
