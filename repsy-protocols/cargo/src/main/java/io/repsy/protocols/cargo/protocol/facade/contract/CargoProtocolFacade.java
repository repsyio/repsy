package io.repsy.protocols.cargo.protocol.facade.contract;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@NullMarked
public interface CargoProtocolFacade<ID> {

  List<CrateIndexEntry> getIndexEntries(ProtocolContext context);

  Resource download(ProtocolContext context);

  void publish(ProtocolContext context, InputStream inputStream) throws IOException;

  void yank(ProtocolContext context);

  void unyank(ProtocolContext context);

  Page<CrateListItem> search(ProtocolContext context, String query, Pageable pageable);

  CrateInfo getCrate(ProtocolContext context);

  CrateVersionInfo getCrateVersion(ProtocolContext context);
}
