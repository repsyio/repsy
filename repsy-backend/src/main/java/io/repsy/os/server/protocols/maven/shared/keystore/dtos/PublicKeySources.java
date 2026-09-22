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
package io.repsy.os.server.protocols.maven.shared.keystore.dtos;

import java.util.List;

/**
 * Everywhere a repo's Maven key store lets {@link
 * io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService} look for a
 * signer's public key (RPS-1189): its own registered armored keys, tried first, then the key-server
 * hosts the repo allows (the two hardcoded defaults are tried after that, unconditionally).
 *
 * @param registeredArmoredKeys armored public key blocks registered directly on the repo
 * @param keyServerHosts the repo's allowed key-server hosts
 */
public record PublicKeySources(List<String> registeredArmoredKeys, List<String> keyServerHosts) {

  public static PublicKeySources none() {
    return new PublicKeySources(List.of(), List.of());
  }
}
