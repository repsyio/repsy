package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@JsonIgnoreProperties(ignoreUnknown = true)
public record CratePublishRequest(
    String name,
    String vers,
    @Nullable List<CratePublishDep> deps,
    @Nullable Map<String, List<String>> features,
    List<String> authors,
    @Nullable String description,
    @Nullable String documentation,
    @Nullable String homepage,
    @Nullable String readme,
    @JsonProperty("readme_file") @Nullable String readmeFile,
    List<String> keywords,
    List<String> categories,
    @Nullable String license,
    @JsonProperty("license_file") @Nullable String licenseFile,
    @Nullable String repository,
    @Nullable String links,
    @JsonProperty("rust_version") @Nullable String rustVersion,
    @Nullable String cksum,
    @JsonProperty("features2") @Nullable Map<String, List<String>> features2) {}
