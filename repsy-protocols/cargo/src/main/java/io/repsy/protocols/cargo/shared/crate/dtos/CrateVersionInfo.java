package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record CrateVersionInfo(
    UUID crateId,
    String name,
    String version,
    @Nullable String readme,
    @Nullable String license,
    @JsonProperty("license_file") @Nullable String licenseFile,
    @Nullable String documentation,
    @Nullable String edition,
    @JsonProperty("rust_version") @Nullable String rustVersion,
    long downloads,
    @JsonProperty("created_at") Instant createdAt
) {}
