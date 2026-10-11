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
package io.repsy.os.server.protocols.ruby.protocol.pre_processors;

import io.repsy.os.server.protocols.ruby.shared.auth.services.RubyAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import org.springframework.stereotype.Component;

/** RubyGems: Basic or Bearer; {@code gem push} sends its API key without a scheme. */
@Component
public class RubyAuthPreProcessor extends BasicOrBearerAuthPreProcessor<RubyAuthenticator> {

  public RubyAuthPreProcessor(
      final RubyAuthenticator authenticator, final RubyProtocolProvider provider) {
    super(authenticator, provider);
  }

  @Override
  protected boolean acceptsBareToken() {
    return true;
  }
}
