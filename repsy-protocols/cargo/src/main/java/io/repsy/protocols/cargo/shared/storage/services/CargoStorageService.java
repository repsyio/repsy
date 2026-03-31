package io.repsy.protocols.cargo.shared.storage.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import java.io.IOException;
import java.util.UUID;

import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface CargoStorageService<ID> {

    BaseUsages writeCrateAndIndex(
        UUID repoId,
        String repoName,
        String crateName,
        String versionName,
        byte[] crateBytes,
        String indexJsonLine) throws IOException;

    Resource getCrate(UUID repoId, String repoName, String crateName, String versionName);

    Resource getIndex(UUID repoId, String repoName, String crateName);

    long deleteCrate(UUID repoId, String repoName, String crateName, String versionName);

    long deletePackage(UUID repoId, String crateName);
}
