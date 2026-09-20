/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.shared.utils;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} that starts at an arbitrary row offset.
 *
 * <p>{@link org.springframework.data.domain.PageRequest} can only start at a multiple of its page
 * size, so a {@code skip}/{@code take} pair whose {@code skip} is not a multiple of {@code take}
 * has to be rounded down to a page boundary and serves the wrong window. This one carries the exact
 * offset, so Spring Data reads exactly rows {@code offset} to {@code offset + pageSize - 1}, and
 * still reports the total the way it does for any page.
 *
 * @param offset the index of the first row to return, not negative
 * @param pageSize the number of rows to return, at least 1
 * @param sort the sort to apply
 */
@NullMarked
public record OffsetPageRequest(long offset, int pageSize, Sort sort) implements Pageable {

  public OffsetPageRequest {
    if (offset < 0) {
      throw new IllegalArgumentException("Offset must not be negative: " + offset);
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("Page size must be at least 1: " + pageSize);
    }
  }

  /** Creates a request for {@code pageSize} rows starting at {@code offset}, in no set order. */
  public static OffsetPageRequest of(final long offset, final int pageSize) {
    return new OffsetPageRequest(offset, pageSize, Sort.unsorted());
  }

  @Override
  public int getPageNumber() {
    return (int) (this.offset / this.pageSize);
  }

  @Override
  public int getPageSize() {
    return this.pageSize;
  }

  @Override
  public long getOffset() {
    return this.offset;
  }

  @Override
  public Sort getSort() {
    return this.sort;
  }

  @Override
  public Pageable next() {
    return new OffsetPageRequest(this.offset + this.pageSize, this.pageSize, this.sort);
  }

  @Override
  public Pageable previousOrFirst() {
    return this.hasPrevious()
        ? new OffsetPageRequest(Math.max(this.offset - this.pageSize, 0), this.pageSize, this.sort)
        : this.first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageRequest(0, this.pageSize, this.sort);
  }

  @Override
  public Pageable withPage(final int pageNumber) {
    return new OffsetPageRequest((long) pageNumber * this.pageSize, this.pageSize, this.sort);
  }

  @Override
  public boolean hasPrevious() {
    return this.offset > 0;
  }
}
