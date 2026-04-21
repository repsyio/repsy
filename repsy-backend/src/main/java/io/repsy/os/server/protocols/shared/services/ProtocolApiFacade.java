package io.repsy.os.server.protocols.shared.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.io.IOException;
import java.util.UUID;

public interface ProtocolApiFacade {

  void createRepo(final UUID repoId);

  BaseUsages deleteRepo(final RepoInfo repoInfo) throws IOException;
}
