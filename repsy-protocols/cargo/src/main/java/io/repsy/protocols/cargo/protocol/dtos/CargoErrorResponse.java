package io.repsy.protocols.cargo.protocol.dtos;

import java.util.List;

public record CargoErrorResponse(List<CargoErrorDetail> errors) {
  public static CargoErrorResponse of(final String detail) {
    return new CargoErrorResponse(List.of(new CargoErrorDetail(detail)));
  }
}
