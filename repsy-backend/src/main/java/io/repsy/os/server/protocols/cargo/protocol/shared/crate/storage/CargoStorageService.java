package io.repsy.os.server.protocols.cargo.protocol.shared.crate.storage;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.cargo.shared.storage.services.AbstractCargoStorageService;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class CargoStorageService extends AbstractCargoStorageService {

  public CargoStorageService(
      @Qualifier("osStorageStrategyCargo") final StorageStrategy storageStrategy) {
    super(storageStrategy);
  }
}
