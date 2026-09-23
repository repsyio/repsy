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
package io.repsy.protocols.ruby.protocol.facades.contract;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface RubyProtocolFacade {

  String getNames(ProtocolContext context);

  String getVersionsIndex(ProtocolContext context);

  String getGemInfo(ProtocolContext context, String gemName);

  Resource downloadGem(ProtocolContext context, String filename);

  /** Cheap existence check for {@code /info/<gemName>}, used by HEAD (RPS-1237). */
  boolean gemExists(ProtocolContext context, String gemName);

  /** Cheap existence check for {@code /gems/<filename>.gem}, used by HEAD (RPS-1237). */
  boolean gemFileExists(ProtocolContext context, String filename);

  /**
   * Cheap existence check for {@code /quick/Marshal.4.8/<name>-<version>.gemspec.rz}, used by HEAD
   * (RPS-1237). Mirrors {@link #getGemspec}'s own filtering: a yanked version does not count.
   */
  boolean gemspecExists(ProtocolContext context, String name, String version);

  byte[] getGemspec(ProtocolContext context, String name, String version);

  void publishGem(ProtocolContext context, SpooledUpload gem) throws IOException;

  void yankGem(ProtocolContext context, String gemName, String version, String platform);

  byte[] getSpecs(ProtocolContext context);

  byte[] getLatestSpecs(ProtocolContext context);

  byte[] getPrereleaseSpecs(ProtocolContext context);
}
