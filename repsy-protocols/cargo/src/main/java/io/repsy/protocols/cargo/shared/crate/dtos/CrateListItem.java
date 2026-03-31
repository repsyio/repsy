package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record CrateListItem(
    String name, @JsonProperty("max_version") String maxVersion, @Nullable String description) {}
