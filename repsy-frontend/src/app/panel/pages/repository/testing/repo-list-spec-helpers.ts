///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.

// Test-only helper shared by the specs of the panel list components (`*-list` and `*-version-list`). It registers the
// behavior they all have in common (repository selection, permissions, paging, searching, sorting, the security
// summary watch, cleanup on destroy and the delete confirmation); what differs per protocol (constructor arguments,
// service methods, argument positions, navigation after a delete) stays in the individual spec. This file is not a
// spec, and nothing under src/app imports it, so it is never part of the application bundle.

import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { RepoPermissionInfo, VersionSecuritySummary } from '../../../../../generated/api';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { PagedData } from '../../../shared/dto/paged-data';
import { Sort } from '../../../shared/dto/sort';
import { permission } from './protocol-service-spec-helpers';

/** The members every list component exposes; the concrete components are structurally assignable to it. */
export interface ListLike {
  loading: boolean;
  pageNum: number;
  searchText?: string;
  sortOption: Sort;
  sortOptions: Sort[];
  pagedData: PagedData<unknown>;
  securitySummary?: Record<string, VersionSecuritySummary>;
  readonly canManage: boolean;
  loadPage(pageNum: number): void;
  refreshPage(): void;
  sort(option: Sort): void;
  search?(text: string): void;
  ngOnDestroy(): void;
}

/** A component under test with the spies it was built on. */
export interface ListFixture {
  component: ListLike;
  /** The `repoChanges` subject the component subscribed to. */
  repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  /** The service method that loads a page of the listing. */
  load: jasmine.Spy;
  /** Positions in the argument list of `load`; `search` is left out when the loader takes no search text. */
  args: { page: number; sort: number; search?: number };
  /** Makes `load` answer with this page. */
  respond(content: unknown[], totalPages: number): void;
  /** Makes `load` fail. */
  fail(): void;
  /** The security-summary watch, with the arguments it must be called with for `repoName`; needs `options.security`. */
  security?: { watch: jasmine.Spy; argsFor: (repoName: string) => unknown[] };
}

/** What differs between the list components, known when the specs are registered. */
export interface ListOptions {
  /** True when the component watches a security summary (then `fixture.security` must be set). */
  security: boolean;
  /** Set when the component keeps the failure text on `error`, to what it must read after a failed fetch. */
  failureMessage?: string;
  /** True when sorting restarts from the first page. */
  sortResetsPage?: boolean;
  /** Set when the component can be searched; `typed` is what is entered, `loaded` what reaches the loader. */
  search?: { typed: string; loaded: string };
}

export const REPO_NAME = 'acme-repo';

export function pageOf(content: unknown[], totalPages: number): PagedData<unknown> {
  const paged = new PagedData<unknown>();
  paged.content = content;
  paged.page = { number: 0, size: 10, totalElements: content.length, totalPages };
  return paged;
}

/** Registers the behavior shared by all list components; `setup` builds a fresh fixture for every spec. */
export function describeRepoListBehavior(setup: () => ListFixture, options: ListOptions): void {
  let fixture: ListFixture;

  beforeEach(() => {
    fixture = setup();
    fixture.respond([{ id: 1 }, { id: 2 }], 3);
  });

  afterEach(() => fixture.component.ngOnDestroy());

  /** Selects a repository and lets the fetch settle. */
  function selectRepo(flags: Parameters<typeof permission>[1] = { canManage: true }): void {
    fixture.repoChanges.next(permission(REPO_NAME, flags));
    flushMicrotasks();
  }

  function security(): NonNullable<ListFixture['security']> {
    if (!fixture.security) {
      throw new Error('The fixture has no security watch although options.security is set');
    }
    return fixture.security;
  }

  function lastArgs(): unknown[] {
    return fixture.load.calls.mostRecent().args;
  }

  describe('before a repository is selected', () => {
    it('starts loading, with no permissions, and fetches nothing', () => {
      expect(fixture.component.loading).toBeTrue();
      expect(fixture.component.canManage).toBeFalse();
      expect(fixture.component.pageNum).toBe(0);
      expect(fixture.load).not.toHaveBeenCalled();
    });

    it('offers its default sort among the sort options', () => {
      expect(fixture.component.sortOptions).toContain(fixture.component.sortOption);
    });
  });

  describe('when a repository is selected', () => {
    it('fetches the first page, ten at a time, sorted by the default option', fakeAsync(() => {
      selectRepo();

      expect(fixture.load).toHaveBeenCalledTimes(1);
      expect(lastArgs()[fixture.args.page]).toBe(0);
      expect(lastArgs()[fixture.args.sort]).toBe(fixture.component.sortOption);
      expect(fixture.component.pagedData.page.totalPages).toBe(3);
      expect(fixture.component.loading).toBeFalse();
    }));

    it('lets only a repository manager manage', fakeAsync(() => {
      selectRepo({ canManage: true });
      expect(fixture.component.canManage).toBeTrue();

      selectRepo({ canManage: false });
      expect(fixture.component.canManage).toBeFalse();
    }));

    it('refetches when another repository is selected', fakeAsync(() => {
      selectRepo();
      selectRepo();

      expect(fixture.load).toHaveBeenCalledTimes(2);
    }));

    it('ignores an empty repository value', fakeAsync(() => {
      fixture.repoChanges.next(null);
      flushMicrotasks();

      expect(fixture.load).not.toHaveBeenCalled();
      expect(fixture.component.canManage).toBeFalse();
    }));

    it('stops loading and keeps the previous listing when the fetch fails', fakeAsync(() => {
      selectRepo();
      fixture.fail();

      fixture.component.refreshPage();
      flushMicrotasks();

      expect(fixture.component.loading).toBeFalse();
      expect(fixture.component.pagedData.page.totalPages).toBe(3);
    }));

    if (options.failureMessage !== undefined) {
      it('reports the failure when the fetch fails', fakeAsync(() => {
        fixture.fail();

        selectRepo();

        expect((fixture.component as unknown as { error: string }).error).toBe(options.failureMessage);
      }));
    }
  });

  describe('paging, searching and sorting', () => {
    beforeEach(fakeAsync(() => {
      selectRepo();
      fixture.load.calls.reset();
    }));

    it('loadPage fetches the requested page', fakeAsync(() => {
      fixture.component.loadPage(2);
      flushMicrotasks();

      expect(fixture.component.pageNum).toBe(2);
      expect(fixture.load).toHaveBeenCalledTimes(1);
      expect(lastArgs()[fixture.args.page]).toBe(2);
    }));

    it('sort fetches with the chosen order', fakeAsync(() => {
      const option = fixture.component.sortOptions[fixture.component.sortOptions.length - 1];

      fixture.component.sort(option);
      flushMicrotasks();

      expect(fixture.component.sortOption).toBe(option);
      expect(fixture.load).toHaveBeenCalledTimes(1);
      expect(lastArgs()[fixture.args.sort]).toBe(option);
    }));

    it('sort keeps or restarts the page as the component defines', fakeAsync(() => {
      fixture.component.loadPage(2);
      flushMicrotasks();

      fixture.component.sort(fixture.component.sortOptions[fixture.component.sortOptions.length - 1]);
      flushMicrotasks();

      expect(fixture.component.pageNum).toBe(options.sortResetsPage ? 0 : 2);
      expect(lastArgs()[fixture.args.page]).toBe(fixture.component.pageNum);
    }));

    if (options.search) {
      const { typed, loaded } = options.search;
      it('search restarts from the first page and keeps the text for later pages', fakeAsync(() => {
        fixture.component.loadPage(2);
        flushMicrotasks();

        fixture.component.search(typed);
        flushMicrotasks();

        expect(fixture.component.pageNum).toBe(0);
        expect(lastArgs()[fixture.args.search]).toBe(loaded);
        expect(lastArgs()[fixture.args.page]).toBe(0);

        fixture.component.loadPage(1);
        flushMicrotasks();

        expect(lastArgs()[fixture.args.search]).toBe(loaded);
        expect(lastArgs()[fixture.args.page]).toBe(1);
      }));
    }

    it('refreshPage repeats the current query', fakeAsync(() => {
      fixture.component.loadPage(1);
      flushMicrotasks();
      const before = lastArgs();

      fixture.component.refreshPage();
      flushMicrotasks();

      expect(fixture.load).toHaveBeenCalledTimes(2);
      expect(lastArgs()).toEqual(before);
    }));
  });

  if (options.security) {
    describe('the security summary', () => {
      it('is watched for the selected repository and shown as it changes', fakeAsync(() => {
        const watched = new Subject<Record<string, VersionSecuritySummary>>();
        security().watch.and.returnValue(watched);
        selectRepo();
        const summary = { x: { scanned: true, findingCount: 2 } as VersionSecuritySummary };

        watched.next(summary);

        expect(security().watch).toHaveBeenCalledOnceWith(...security().argsFor(REPO_NAME));
        expect(fixture.component.securitySummary).toEqual(summary);
      }));

      it('drops the earlier watch when another repository is selected', fakeAsync(() => {
        const first = new Subject<Record<string, VersionSecuritySummary>>();
        const second = new Subject<Record<string, VersionSecuritySummary>>();
        security().watch.and.returnValues(first, second);

        selectRepo();
        selectRepo();

        expect(first.observed).toBeFalse();
        expect(second.observed).toBeTrue();
      }));

      it('does not fail the page when it cannot be loaded', fakeAsync(() => {
        security().watch.and.returnValue(throwError(() => new Error('boom')));

        selectRepo();

        expect(fixture.component.securitySummary).toEqual({});
        expect(fixture.component.loading).toBeFalse();
      }));
    });
  }

  describe('ngOnDestroy', () => {
    it('stops following repository changes', fakeAsync(() => {
      fixture.component.ngOnDestroy();

      selectRepo();

      expect(fixture.load).not.toHaveBeenCalled();
      if (options.security) {
        expect(security().watch).not.toHaveBeenCalled();
      }
    }));

    if (options.security) {
      it('stops watching the security summary', fakeAsync(() => {
        const watched = new Subject<Record<string, VersionSecuritySummary>>();
        security().watch.and.returnValue(watched);
        selectRepo();
        expect(watched.observed).toBeTrue();

        fixture.component.ngOnDestroy();

        expect(watched.observed).toBeFalse();
      }));
    }
  });
}

/** What a delete flow needs to be checked; `invoke` calls the component's delete method for a fixed item. */
export interface DeleteFixture {
  list: ListFixture;
  dangerModal: DangerModalService;
  toast: jasmine.Spy;
  /** The service method that deletes. */
  remove: jasmine.Spy;
  invoke(): void;
  title: string;
  message: string;
  /** The arguments `remove` must receive. */
  removeArgs: unknown[];
  /** Set when the component itself toasts a failed delete (`'boom'` is what the stubbed delete fails with). */
  failureToast?: string;
}

/** Registers the confirm, delete, toast and refresh flow of the simple delete (refresh in place) of a list. */
export function describeSimpleDelete(setup: () => DeleteFixture): void {
  let fixture: DeleteFixture;

  beforeEach(fakeAsync(() => {
    fixture = setup();
    fixture.list.respond([{ id: 1 }], 1);
    fixture.remove.and.returnValue(of(undefined));
    fixture.list.repoChanges.next(permission(REPO_NAME, { canManage: true }));
    flushMicrotasks();
    fixture.list.load.calls.reset();
  }));

  afterEach(() => fixture.list.component.ngOnDestroy());

  it('asks for confirmation before deleting anything', () => {
    fixture.invoke();

    expect(fixture.dangerModal.modal).toEqual({ title: fixture.title, action: 'Delete', message: null });
    expect(fixture.remove).not.toHaveBeenCalled();
  });

  it('deletes once the modal confirms, then toasts and refetches the listing', fakeAsync(() => {
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.remove).toHaveBeenCalledOnceWith(...fixture.removeArgs);
    expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.message, 'success');
    expect(fixture.list.load).toHaveBeenCalledTimes(1);
    expect(fixture.list.component.loading).toBeFalse();
  }));

  it('neither toasts nor refetches, and stops loading, when the delete fails', fakeAsync(() => {
    fixture.remove.and.returnValue(throwError(() => 'boom'));
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    if (fixture.failureToast === undefined) {
      expect(fixture.toast).not.toHaveBeenCalled();
    } else {
      expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.failureToast, 'error');
    }
    expect(fixture.list.load).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));
}

/** A delete fixture whose component leaves the listing for the repository page once the last item is gone. */
export interface EmptyingDeleteFixture extends DeleteFixture {
  /** The router method the component navigates with; it must answer with a resolved promise. */
  navigate: jasmine.Spy;
  /** The arguments that method must receive. */
  navigateArgs: unknown[];
}

/**
 * Registers the delete flow of a listing that navigates away when its last item is deleted, and refreshes in place
 * otherwise.
 */
export function describeEmptyingDelete(setup: () => EmptyingDeleteFixture): void {
  let fixture: EmptyingDeleteFixture;

  function openListing(itemCount: number): void {
    fixture.list.respond(
      Array.from({ length: itemCount }, (_, id) => ({ id })),
      1,
    );
    fixture.list.repoChanges.next(permission(REPO_NAME, { canManage: true }));
    flushMicrotasks();
    fixture.list.load.calls.reset();
  }

  beforeEach(() => {
    fixture = setup();
    fixture.remove.and.returnValue(of(undefined));
    fixture.navigate.and.returnValue(Promise.resolve(true));
  });

  afterEach(() => fixture.list.component.ngOnDestroy());

  it('asks for confirmation before deleting anything', fakeAsync(() => {
    openListing(2);

    fixture.invoke();

    expect(fixture.dangerModal.modal).toEqual({ title: fixture.title, action: 'Delete', message: null });
    expect(fixture.remove).not.toHaveBeenCalled();
  }));

  it('refreshes the listing and toasts when other items remain', fakeAsync(() => {
    openListing(2);
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.remove).toHaveBeenCalledOnceWith(...fixture.removeArgs);
    expect(fixture.list.load).toHaveBeenCalledTimes(1);
    expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.message, 'success');
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));

  it('leaves for the repository page, and only then toasts, when the last item is deleted', fakeAsync(() => {
    openListing(1);
    fixture.invoke();

    fixture.dangerModal.call();

    expect(fixture.navigate).toHaveBeenCalledOnceWith(...fixture.navigateArgs);
    expect(fixture.toast).not.toHaveBeenCalled();

    flushMicrotasks();

    expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.message, 'success');
    expect(fixture.list.load).not.toHaveBeenCalled();
  }));

  it('neither navigates, refreshes nor toasts when the delete fails', fakeAsync(() => {
    openListing(1);
    fixture.remove.and.returnValue(throwError(() => 'boom'));
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.load).not.toHaveBeenCalled();
    expect(fixture.toast).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));
}

/** A listing whose delete is checked against the page the user is on (RPS-1340). */
export interface PagedDeleteFixture {
  list: ListFixture;
  dangerModal: DangerModalService;
  /** Deletes the one row (a version, a package), never its parent. */
  remove: jasmine.Spy;
  invoke(): void;
  /** The router method the component navigates with. */
  navigate: jasmine.Spy;
  /** What `remove` answers with; a resolved promise for the promise-based services, an observable otherwise. */
  answer?: () => unknown;
}

/**
 * Registers what a paged listing does after a delete when it is not on its first page, or when a search filters it:
 * the parent of the rows is still there, so the listing stays and shows the page before instead of leaving.
 */
export function describePagedDelete(setup: () => PagedDeleteFixture): void {
  let fixture: PagedDeleteFixture;

  /** A listing of `rows` rows on page `pageNum`, in a list of 11 rows (two pages of 10) unless it is searched. */
  function openListing(rows: number, pageNum: number, searchText = ''): void {
    fixture.list.respond(
      Array.from({ length: rows }, (_, id) => ({ id })),
      2,
    );
    fixture.list.repoChanges.next(permission(REPO_NAME, { canManage: true }));
    flushMicrotasks();
    const list = fixture.list.component;
    list.pageNum = pageNum;
    list.searchText = searchText;
    list.pagedData.page = { number: pageNum, size: 10, totalElements: searchText ? rows : 10 + rows, totalPages: 2 };
    fixture.list.load.calls.reset();
  }

  function confirmDelete(): void {
    fixture.invoke();
    fixture.dangerModal.call();
    flushMicrotasks();
  }

  beforeEach(() => {
    fixture = setup();
    fixture.remove.and.callFake(() => (fixture.answer ? fixture.answer() : of(undefined)));
    fixture.navigate.and.returnValue(Promise.resolve(true));
  });

  afterEach(() => fixture.list.component.ngOnDestroy());

  it('goes back a page, and stays, when the only row of the second page is deleted', fakeAsync(() => {
    openListing(1, 1);

    confirmDelete();

    expect(fixture.remove).toHaveBeenCalledTimes(1);
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.load).toHaveBeenCalledTimes(1);
    expect(fixture.list.load.calls.mostRecent().args[fixture.list.args.page]).toBe(0);
    expect(fixture.list.component.pageNum).toBe(0);
  }));

  it('reloads the same page when other rows remain on it', fakeAsync(() => {
    openListing(2, 1);

    confirmDelete();

    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.load.calls.mostRecent().args[fixture.list.args.page]).toBe(1);
  }));

  it('stays on the listing when the only match of a search is deleted: the search hid the other rows', fakeAsync(() => {
    openListing(1, 0, 'v1');

    confirmDelete();

    expect(fixture.remove).toHaveBeenCalledTimes(1);
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.load).toHaveBeenCalledTimes(1);
  }));
}

/** A version listing whose delete removes the whole package when its last version goes. */
export interface LastVersionDeleteFixture {
  list: ListFixture;
  dangerModal: DangerModalService;
  toast: jasmine.Spy;
  invoke(): void;
  title: string;
  message: string;
  removeVersion: jasmine.Spy;
  removeVersionArgs: unknown[];
  removePackage: jasmine.Spy;
  removePackageArgs: unknown[];
  /** The router method the component navigates with. */
  navigate: jasmine.Spy;
  navigateArgs: unknown[];
  /** What the delete calls answer with; a resolved promise for the promise-based services, observables otherwise. */
  answer?: { ok(): unknown; fail(): unknown };
}

/** Registers the delete flow of a version listing: one version, or the whole package when it is the last one. */
export function describeLastVersionDelete(setup: () => LastVersionDeleteFixture): void {
  let fixture: LastVersionDeleteFixture;

  function ok(): unknown {
    return fixture.answer ? fixture.answer.ok() : of(undefined);
  }

  function failure(): unknown {
    return fixture.answer ? fixture.answer.fail() : throwError(() => 'boom');
  }

  function openListing(versionCount: number): void {
    fixture.list.respond(
      Array.from({ length: versionCount }, (_, id) => ({ id })),
      1,
    );
    fixture.list.repoChanges.next(permission(REPO_NAME, { canManage: true }));
    flushMicrotasks();
    fixture.list.load.calls.reset();
  }

  beforeEach(() => {
    fixture = setup();
    fixture.removeVersion.and.callFake(ok);
    fixture.removePackage.and.callFake(ok);
    fixture.navigate.and.returnValue(Promise.resolve(true));
  });

  afterEach(() => fixture.list.component.ngOnDestroy());

  it('asks for confirmation before deleting anything', fakeAsync(() => {
    openListing(2);

    fixture.invoke();

    expect(fixture.dangerModal.modal).toEqual({ title: fixture.title, action: 'Delete', message: null });
    expect(fixture.removeVersion).not.toHaveBeenCalled();
    expect(fixture.removePackage).not.toHaveBeenCalled();
  }));

  it('deletes just the version, toasts and refetches while other versions remain', fakeAsync(() => {
    openListing(2);
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.removeVersion).toHaveBeenCalledOnceWith(...fixture.removeVersionArgs);
    expect(fixture.removePackage).not.toHaveBeenCalled();
    expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.message, 'success');
    expect(fixture.list.load).toHaveBeenCalledTimes(1);
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));

  it('deletes the whole package and leaves the listing when the last version is deleted', fakeAsync(() => {
    openListing(1);
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.removePackage).toHaveBeenCalledOnceWith(...fixture.removePackageArgs);
    expect(fixture.removeVersion).not.toHaveBeenCalled();
    expect(fixture.toast).toHaveBeenCalledOnceWith(fixture.message, 'success');
    expect(fixture.navigate).toHaveBeenCalledOnceWith(...fixture.navigateArgs);
    expect(fixture.list.load).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));

  it('neither toasts, navigates nor refetches when the delete fails', fakeAsync(() => {
    openListing(2);
    fixture.removeVersion.and.callFake(failure);
    fixture.invoke();

    fixture.dangerModal.call();
    flushMicrotasks();

    expect(fixture.toast).not.toHaveBeenCalled();
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.list.load).not.toHaveBeenCalled();
    expect(fixture.list.component.loading).toBeFalse();
  }));
}
