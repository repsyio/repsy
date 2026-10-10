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
package io.repsy.os.server.protocols.golang.protocol.handlers;

import io.repsy.os.server.protocols.shared.handlers.AbstractRepoPathParser;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component("osGolangPathParser")
@NullMarked
public class GoPathParser extends AbstractRepoPathParser {

  public GoPathParser(final RepoTxService repoTxService) {
    super(repoTxService, RepoType.GOLANG, null, strictRepoPattern(""));
  }
}
