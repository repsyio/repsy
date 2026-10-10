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
///
import { DatePipe } from '@angular/common';
import { HttpContext } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import moment from 'moment';
import { concat, Observable, of } from 'rxjs';
import { catchError, finalize, map, switchMap, tap, toArray } from 'rxjs/operators';

import { AccessTokenCreated, AccessTokenListItem, AccessTokensApi } from '../../../../../generated/api';
import { SILENT_ERROR } from '../../../../shared/interceptors/error-handler.interceptor';
import { EmptyListComponent } from '../../../shared/components/empty-list/empty-list.component';
import { AccessTokenCreateModalComponent } from '../../../shared/components/modals/access-token-create-modal/access-token-create-modal.component';
import { AccessTokenInfoModalComponent } from '../../../shared/components/modals/access-token-info-modal/access-token-info-modal.component';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { PaginationComponent } from '../../../shared/components/pagination/pagination.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { PagedData } from '../../../shared/dtos/paged-data';
import { countLive, isExpired, MAX_LIVE_ACCESS_TOKENS } from './access-token-limits';

@Component({
  selector: 'app-access-tokens',
  imports: [
    DatePipe,
    PaginationComponent,
    EmptyListComponent,
    AccessTokenCreateModalComponent,
    AccessTokenInfoModalComponent,
  ],
  standalone: true,
  templateUrl: './access-tokens.component.html',
})
export class AccessTokensComponent implements OnInit {
  pageNum = 0;
  readonly pageSize = 5;
  operationLock = false;
  tokens: AccessTokenListItem[] = [];
  pagedData = new PagedData<AccessTokenListItem>();
  createdToken: AccessTokenCreated;
  showCreateModal = false;
  showInfoModal = false;
  /** Tokens that have not expired: the limit applies to them, and a password change warns about them. */
  liveCount = 0;
  readonly maxLive = MAX_LIVE_ACCESS_TOKENS;
  /** The outcome of the last "revoke all": how many were revoked and the names that failed. */
  revokeAllReport: { revoked: number; failed: string[] } | null = null;

  constructor(
    private readonly accessTokensApi: AccessTokensApi,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {}

  ngOnInit(): void {
    this.fetchTokens();
  }

  get limitReached(): boolean {
    return this.liveCount >= this.maxLive;
  }

  fetchTokens(): void {
    this.listPage(this.pageNum).subscribe({ next: (r) => this.showTokens(r), error: () => {} });
    this.refreshLiveCount();
  }

  /**
   * The tokens that have not expired. They sort first by expiration, newest first, and there are at
   * most 50 of them, so the first 100 hold every one.
   */
  private refreshLiveCount(): void {
    this.accessTokensApi.listAccessTokens(0, 100, ['expirationDate,desc']).subscribe({
      next: (r) => {
        this.liveCount = countLive(r.content ?? []);
      },
      error: () => {},
    });
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchTokens();
  }

  openCreate(): void {
    this.showCreateModal = true;
  }

  onCreated(token: AccessTokenCreated): void {
    this.createdToken = token;
    this.showInfoModal = true;
    this.fetchTokens();
  }

  closeInfo(open: boolean): void {
    this.showInfoModal = open;
    if (!open) {
      this.createdToken = undefined as never;
    }
  }

  // One chained request, like the deploy token revoke: the list is fetched once, after the revoke
  // has completed, for the page that is left.
  revokeToken(token: AccessTokenListItem): void {
    this.dangerModalService.show('Revoke Access Token', 'Revoke', () => {
      this.operationLock = true;
      this.accessTokensApi
        .revokeAccessToken(token.id)
        .pipe(
          tap(() => this.toastService.show('Access token revoked successfully', 'success')),
          switchMap(() => {
            this.pageNum = this.pageAfterRevoke();
            this.refreshLiveCount();
            return this.listPage(this.pageNum);
          }),
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe({ next: (r) => this.showTokens(r), error: () => {} });
    });
  }

  /** The scopes of a token as text (the wire value is an array, the generated type a Set). */
  scopesText(token: AccessTokenListItem): string {
    return Array.from(token.scopes).join(', ');
  }

  isExpired(token: AccessTokenListItem): boolean {
    return isExpired(token);
  }

  /**
   * Revokes every token (expired ones too), one DELETE after the other (there is no bulk route). A
   * token that cannot be revoked does not stop the rest; the names that failed are reported. One
   * run handles the first 100 (expired ones sort last); run it again for more.
   */
  revokeAll(): void {
    this.dangerModalService.show('Revoke All Access Tokens', 'Revoke all', () => {
      this.operationLock = true;
      this.revokeAllReport = null;
      this.collectAll()
        .pipe(
          switchMap((all) => this.revokeEach(all)),
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe((report) => {
          this.revokeAllReport = report;
          this.pageNum = 0;
          this.toastService.show(
            report.failed.length === 0
              ? `${report.revoked} access tokens revoked`
              : `${report.revoked} revoked, ${report.failed.length} could not be revoked`,
            report.failed.length === 0 ? 'success' : 'error',
          );
          this.fetchTokens();
        });
    });
  }

  private collectAll(): Observable<AccessTokenListItem[]> {
    return this.accessTokensApi.listAccessTokens(0, 100, ['expirationDate,desc']).pipe(
      map((r) => r.content ?? []),
      // A list that cannot be read revokes nothing.
      catchError(() => of([] as AccessTokenListItem[])),
    );
  }

  private revokeEach(all: AccessTokenListItem[]): Observable<{ revoked: number; failed: string[] }> {
    const silent = { context: new HttpContext().set(SILENT_ERROR, true) };
    return concat(
      ...all.map((t) =>
        this.accessTokensApi.revokeAccessToken(t.id, 'body', false, silent).pipe(
          map(() => null as string | null),
          catchError(() => of(t.name)),
        ),
      ),
    ).pipe(
      toArray(),
      map((outcomes) => ({
        revoked: outcomes.filter((o) => o === null).length,
        failed: outcomes.filter((o): o is string => o !== null),
      })),
    );
  }

  timeAgo(date: string | undefined): string {
    return date ? moment(date).fromNow() : 'Never';
  }

  expiryClass(expirationDate: string): string {
    const date = moment(expirationDate);
    if (date.isSameOrBefore(moment())) {
      return 'text-error-500';
    }
    return date.diff(moment(), 'days') <= 7 ? 'text-warning-500' : 'text-[#FDFDFD]';
  }

  private listPage(pageNum: number) {
    return this.accessTokensApi.listAccessTokens(pageNum, this.pageSize);
  }

  private showTokens(r: { content?: AccessTokenListItem[]; page?: unknown }): void {
    this.pagedData.page = { ...(r.page as object) } as PagedData<AccessTokenListItem>['page'];
    this.tokens = r.content ?? [];
  }

  private pageAfterRevoke(): number {
    const total = this.pagedData.page?.totalElements ?? this.pageNum * this.pageSize + this.tokens.length;
    const lastPage = Math.max(0, Math.ceil((total - 1) / this.pageSize) - 1);
    return Math.min(this.pageNum, lastPage);
  }
}
