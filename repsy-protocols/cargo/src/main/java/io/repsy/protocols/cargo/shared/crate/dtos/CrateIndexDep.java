package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrateIndexDep(
    String name,
    String req,
    List<String> features,
    boolean optional,
    @JsonProperty("default_features") boolean defaultFeatures,
    @Nullable String target,
    String kind,
    @Nullable String registry,
    @JsonProperty("package") @Nullable String packageName
) {}
