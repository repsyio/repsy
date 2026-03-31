package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record CratePublishDep(
    String name,
    @JsonProperty("version_req") String versionReq,
    List<String> features,
    boolean optional,
    @JsonProperty("default_features") boolean defaultFeatures,
    @Nullable String target,
    String kind,
    @Nullable String registry,
    @JsonProperty("explicit_name_in_toml") @Nullable String explicitNameInToml) {}
