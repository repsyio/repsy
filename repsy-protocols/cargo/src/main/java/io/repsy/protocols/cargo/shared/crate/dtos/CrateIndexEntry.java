package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrateIndexEntry(
    String name,
    String vers,
    List<CrateIndexDep> deps,
    String cksum,
    Map<String, List<String>> features,
    boolean yanked,
    @Nullable String links,
    int v,
    @JsonProperty("features2") @Nullable Map<String, List<String>> features2,
    @JsonProperty("rust_version") @Nullable String rustVersion
) {}
