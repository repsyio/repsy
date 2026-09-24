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

import { CommonModule, NgClass, NgOptimizedImage } from '@angular/common';
import { Component, Input, OnInit } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import moment from 'moment';
import { finalize, switchMap, tap } from 'rxjs/operators';

import { environment } from '../../../../../../environments/environment';
import {
  DeployTokenInfoListItem,
  ProtocolDeployTokenControllerService,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  RepoUsageInfo,
  RestResponsePagedModelDeployTokenInfoListItem,
} from '../../../../../../generated/api';
import { EmptyListComponent } from '../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { DeployTokenCreateModalComponent } from '../../../../shared/components/modals/deploy-token-create-modal/deploy-token-create-modal.component';
import { DeployTokenInfoModalComponent } from '../../../../shared/components/modals/deploy-token-info-modal/deploy-token-info-modal.component';
import { PaginationComponent } from '../../../../shared/components/pagination/pagination.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../shared/dto/paged-data';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { CargoConfigComponent } from '../../cargo/config/cargo-config.component';
import { DockerConfigComponent } from '../../docker/config/docker-config.component';
import { GolangConfigComponent } from '../../golang/config/golang-config.component';
import { HelmConfigComponent } from '../../helm/config/helm-config.component';
import { MavenConfigComponent } from '../../maven/config/maven-config.component';
import { NpmConfigComponent } from '../../npm/config/npm-config.component';
import { NugetConfigComponent } from '../../nuget/config/nuget-config.component';
import { PypiConfigComponent } from '../../pypi/config/pypi-config.component';
import { RubyConfigComponent } from '../../ruby/config/ruby-config.component';
import { TokenCreateInfo } from './dto/token-create-info';

@Component({
  selector: 'app-deploy-token',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    DeployTokenCreateModalComponent,
    PaginationComponent,
    NgOptimizedImage,
    DeployTokenInfoModalComponent,
    TooltipComponent,
    EmptyListComponent,
    DockerConfigComponent,
    CargoConfigComponent,
    GolangConfigComponent,
    HelmConfigComponent,
    NgClass,
    MavenConfigComponent,
    NugetConfigComponent,
    PypiConfigComponent,
    NpmConfigComponent,
    RubyConfigComponent,
    CommonModule,
    RouterLink,
  ],
  templateUrl: './deploy-token.component.html',
  styleUrl: './deploy-token.component.css',
})
export class DeployTokenComponent implements OnInit {
  @Input() public activeRepository: RepoPermissionInfo;
  @Input() public repoType: string;

  public operationLock = false;
  public pageNum = 0;
  public pageSize = 3;
  public deployTokens: DeployTokenInfoListItem[];
  public pagedData: PagedData<DeployTokenInfoListItem>;
  public createdDeployToken: TokenCreateInfo;
  public showCreateTokenModal = false;
  public showTokenInfoModal = false;
  public showConfig = false;
  public selectedDeployToken: DeployTokenInfoListItem;
  public repoUsage: RepoUsageInfo;

  constructor(
    private readonly protocolDeployTokenControllerService: ProtocolDeployTokenControllerService,
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.pagedData = new PagedData<DeployTokenInfoListItem>();
  }

  ngOnInit(): void {
    this.fetchDeployTokens();
  }

  private fetchRepoUsage() {
    this.protocolRepoControllerService.getUsage(this.activeRepository.repoName).subscribe({
      next: (r) => {
        this.repoUsage = r.data!;
      },
      error: () => {},
    });
  }

  public fetchDeployTokens() {
    this.fetchRepoUsage();
    this.listPage(this.pageNum).subscribe({
      next: (r) => this.showTokens(r),
      error: () => {},
    });
  }

  private listPage(pageNum: number) {
    return this.protocolDeployTokenControllerService.listDeployTokens(
      this.activeRepository.repoName,
      pageNum,
      this.pageSize,
    );
  }

  private showTokens(r: RestResponsePagedModelDeployTokenInfoListItem) {
    this.pagedData.page = { ...r.data?.page } as PagedData<DeployTokenInfoListItem>['page'];
    this.deployTokens = r.data?.content ?? [];
  }

  /**
   * The page to show once one token is gone: the current page, or the new last page when the
   * revoked token was the only one on a page that no longer exists.
   */
  private pageAfterRevoke(): number {
    const total = this.pagedData.page?.totalElements ?? this.pageNum * this.pageSize + (this.deployTokens?.length ?? 0);
    const lastPage = Math.max(0, Math.ceil((total - 1) / this.pageSize) - 1);
    return Math.min(this.pageNum, lastPage);
  }

  public loadPage(pageNum: number) {
    this.pageNum = pageNum;
    this.fetchDeployTokens();
  }

  public createDeployToken(): void {
    this.showCreateTokenModal = true;
  }

  public rotateDeployToken(deployToken: DeployTokenInfoListItem) {
    const successMsg = 'Deploy token rotated successfully';
    this.dangerModalService.show('Rotate Deploy Token', 'Rotate', () => {
      this.operationLock = true;

      this.protocolDeployTokenControllerService
        .rotate(deployToken.id, this.activeRepository.repoName)
        .pipe(
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe({
          next: (r) => {
            this.fetchDeployTokens();
            this.toastService.show(successMsg, 'success');
            this.createdDeployToken = new TokenCreateInfo();
            this.createdDeployToken.token = r.data!;
            this.createdDeployToken.username = deployToken.username;
            this.createdDeployToken.id = deployToken.id;
            this.showTokenInfoModal = true;
          },
          error: () => {},
        });
    });
  }

  // RPS-1285: one chained request. The list is fetched once, after the revoke has completed, for the
  // page that is left (never for a page past the end), so there is no second answer to race it.
  public revokeDeployToken(deployToken: DeployTokenInfoListItem) {
    this.dangerModalService.show('Delete Deploy Token', 'Delete', () => {
      this.operationLock = true;

      this.protocolDeployTokenControllerService
        .revoke(deployToken.id, this.activeRepository.repoName)
        .pipe(
          tap(() => this.toastService.show('Deploy token revoked successfully', 'success')),
          switchMap(() => {
            this.pageNum = this.pageAfterRevoke();
            this.fetchRepoUsage();
            return this.listPage(this.pageNum);
          }),
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe({
          next: (r) => this.showTokens(r),
          error: () => {},
        });
    });
  }

  public configure(deployToken: DeployTokenInfoListItem) {
    this.selectedDeployToken = deployToken;
    this.showConfig = true;
  }

  public openConfigure(open: boolean) {
    this.showConfig = open;
  }

  getBorderForExpireStatus(expireDate: string): string {
    if (!expireDate) {
      return 'text-[#FDFDFD]';
    }

    const date = moment(expireDate);
    const now = moment();

    if (date.isSameOrBefore(now)) {
      return 'text-error-500';
    }

    const diffDays = date.startOf('day').diff(now.startOf('day'), 'days');

    if (diffDays <= 7) {
      return 'text-warning-500';
    }

    return 'text-[#FDFDFD]';
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  protected readonly moment = moment;
  protected readonly environment = environment;
  protected readonly RepoType = RepoType;
}
