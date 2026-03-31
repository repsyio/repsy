package io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities;

import io.repsy.core.uuidv7.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.NonNull;

@Data
@Entity
@Table(name = "cargo_author")
@NoArgsConstructor
@ToString(exclude = "crates")
@EqualsAndHashCode(exclude = "crates")
public class CargoAuthor {

  @Id
  @UuidV7
  @Column(name = "id", columnDefinition = "uuid", nullable = false)
  private UUID id;

  @Column(name = "author", nullable = false, unique = true)
  private String author;

  @ManyToMany(mappedBy = "authors")
  private @NonNull Set<CargoCrate> crates = new HashSet<>();
}
