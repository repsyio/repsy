package io.repsy.os.server.protocols.shared.services;

import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ProtocolApiFacadeMavenAdapter extends ProtocolApiFacade {

  List<StorageItemInfo> getItems(final RepoInfo repoInfo, final RelativePath relativePath);
}
