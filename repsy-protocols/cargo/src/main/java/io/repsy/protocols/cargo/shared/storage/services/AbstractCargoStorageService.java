package io.repsy.protocols.cargo.shared.storage.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractCargoStorageService implements CargoStorageService<UUID> {

    private final StorageStrategy storageStrategy;

    @Override
    public BaseUsages writeCrateAndIndex(
        final UUID repoId,
        final String repoName,
        final String crateName,
        final String versionName,
        final byte[] crateBytes,
        final String indexJsonLine) throws IOException {

        final var crateFileName = String.format("%s-%s.crate", crateName, versionName);
        final var cratePath = Paths.get("crates", crateName, crateFileName);
        final var crateStoragePath = StoragePath.of(repoId, cratePath.toString());

        final BaseUsages crateUsages;
        try (final var bis = new ByteArrayInputStream(crateBytes)) {
            crateUsages = this.storageStrategy.write(repoName, crateStoragePath, bis);
        }

        final var indexPath = this.getIndexPath(crateName);
        final var indexStoragePath = StoragePath.of(repoId, indexPath.toString());

        final var indexData = (indexJsonLine + "\n").getBytes();

        final BaseUsages indexUsages;
        try (final var bis = new ByteArrayInputStream(indexData)) {
            indexUsages = this.storageStrategy.write(repoName, indexStoragePath, bis);
        }

        crateUsages.setDiskUsage(crateUsages.getDiskUsage() + indexUsages.getDiskUsage());
        return crateUsages;
    }

    @Override
    public Resource getCrate(final UUID repoId, final String repoName, final String crateName, final String versionName) {

        final var crateFileName = String.format("%s-%s.crate", crateName, versionName);
        final var cratePath = Paths.get("crates", crateName, crateFileName);
        final var storagePath = StoragePath.of(repoId, cratePath.toString());

        return this.storageStrategy.get(storagePath, repoName)
            .orElseThrow(() -> new ItemNotFoundException("crateNotFound"));
    }

    @Override
    public Resource getIndex(final UUID repoId, final String repoName, final String crateName) {

      final var indexPath = this.getIndexPath(crateName);
        final var storagePath = StoragePath.of(repoId, indexPath.toString());

        return this.storageStrategy.get(storagePath, repoName)
            .orElseThrow(() -> new ItemNotFoundException("indexNotFound"));
    }

    @Override
    public long deleteCrate(final UUID repoId, final String repoName, final String crateName, final String versionName) {

      final var crateFileName = String.format("%s-%s.crate", crateName, versionName);
        final var cratePath = Paths.get("crates", crateName, crateFileName);
        final var storagePath = StoragePath.of(repoId, cratePath.toString());

        final var usage = this.storageStrategy.calculatePathUsage(storagePath);
        this.storageStrategy.delete(storagePath);

        return usage;
    }

    private Path getIndexPath(final String name) {

      final var len = name.length();
        final var basePath = Paths.get("index");

        if (len == 1) {
            return basePath.resolve("1").resolve(name);
        }

        if (len == 2) {
            return basePath.resolve("2").resolve(name);
        }

        if (len == 3) {
            return basePath.resolve("3").resolve(name.substring(0, 1)).resolve(name);
        }

        final var part1 = name.substring(0, 2);
        final var part2 = name.substring(2, 4);

        return basePath.resolve(part1).resolve(part2).resolve(name);
    }
}
