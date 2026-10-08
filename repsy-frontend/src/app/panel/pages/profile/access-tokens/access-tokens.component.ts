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
import { Component, OnInit } from '@angular/core';
import moment from 'moment';
import { finalize, switchMap, tap } from 'rxjs/operators';

import { AccessTokenCreated, AccessTokenListItem, AccessTokensApi } from '../../../../../generated/api';
import { EmptyListComponent } from '../../../shared/components/empty-list/empty-list.component';
import { AccessTokenCreateModalComponent } from '../../../shared/components/modals/access-token-create-modal/access-token-create-modal.component';
import { AccessTokenInfoModalComponent } from '../../../shared/components/modals/access-token-info-modal/access-token-info-modal.component';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { PaginationComponent } from '../../../shared/components/pagination/pagination.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { PagedData } from '../../../shared/dto/paged-data';

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
  public pageNum = 0;
  public readonly pageSize = 5;
  public operationLock = false;
  public tokens: AccessTokenListItem[] = [];
  public pagedData = new PagedData<AccessTokenListItem>();
  public createdToken: AccessTokenCreated;
  public showCreateModal = false;
  public showInfoModal = false;

  constructor(
    private readonly accessTokensApi: AccessTokensApi,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {}

  ngOnInit(): void {
    this.fetchTokens();
  }

  public fetchTokens(): void {
    this.listPage(this.pageNum).subscribe({ next: (r) => this.showTokens(r), error: () => {} });
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchTokens();
  }

  public openCreate(): void {
    this.showCreateModal = true;
  }

  public onCreated(token: AccessTokenCreated): void {
    this.createdToken = token;
    this.showInfoModal = true;
    this.fetchTokens();
  }

  public closeInfo(open: boolean): void {
    this.showInfoModal = open;
    if (!open) {
      this.createdToken = undefined as never;
    }
  }

  // One chained request, like the deploy token revoke: the list is fetched once, after the revoke
  // has completed, for the page that is left.
  public revokeToken(token: AccessTokenListItem): void {
    this.dangerModalService.show('Revoke Access Token', 'Revoke', () => {
      this.operationLock = true;
      this.accessTokensApi
        .revokeAccessToken(token.id)
        .pipe(
          tap(() => this.toastService.show('Access token revoked successfully', 'success')),
          switchMap(() => {
            this.pageNum = this.pageAfterRevoke();
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
  public scopesText(token: AccessTokenListItem): string {
    return Array.from(token.scopes).join(', ');
  }

  public timeAgo(date: string | undefined): string {
    return date ? moment(date).fromNow() : 'Never';
  }

  public expiryClass(expirationDate: string): string {
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
