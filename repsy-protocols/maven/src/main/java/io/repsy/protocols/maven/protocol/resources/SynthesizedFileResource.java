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
package io.repsy.protocols.maven.protocol.resources;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;

/**
 * A file the repository answers with that is not stored: it is generated from what the database
 * knows (RPS-1369). It is a {@link ByteArrayResource}, like a rendered directory listing, but a
 * file: it has a name, and it is served as an attachment, not as {@code text/html}, so a response
 * has to tell the two apart by this type.
 */
@NullMarked
public class SynthesizedFileResource extends ByteArrayResource {

  private final String filename;

  public SynthesizedFileResource(final byte[] content, final String filename) {
    super(content, "synthesized " + filename);

    this.filename = filename;
  }

  @Override
  public String getFilename() {
    return this.filename;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof SynthesizedFileResource that
        && this.filename.equals(that.filename)
        && super.equals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.filename, super.hashCode());
  }
}
