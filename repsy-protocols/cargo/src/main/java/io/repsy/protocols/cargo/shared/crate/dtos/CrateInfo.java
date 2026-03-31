package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record CrateInfo(
    UUID id,
    String name,
    @JsonProperty("original_name") String originalName,
    @JsonProperty("max_version") String maxVersion,
    @JsonProperty("total_downloads") long totalDownloads,
    @Nullable String description,
    @Nullable String homepage,
    @Nullable String repository,
    List<String> authors,
    List<String> keywords,
    List<String> categories) {}
