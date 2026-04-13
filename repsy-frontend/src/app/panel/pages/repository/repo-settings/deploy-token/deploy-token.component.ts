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

import { environment } from '../../../../../../environments/environment';
import { EllipsisPipe } from '../../../../shared/components/ellipsis/ellipsis.pipe';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { DeployTokenCreateModalComponent } from '../../../../shared/components/modals/deploy-token-create-modal/deploy-token-create-modal.component';
import { DeployTokenInfoModalComponent } from '../../../../shared/components/modals/deploy-token-info-modal/deploy-token-info-modal.component';
import { PaginationComponent } from '../../../../shared/components/pagination/pagination.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../shared/dto/paged-data';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { RepoUsageInfo } from '../../../../shared/dto/repo-usage-info';
import { CargoConfigComponent } from '../../cargo/config/cargo-config.component';
import { CargoService } from '../../cargo/service/cargo.service';
import { DockerConfigComponent } from '../../docker/config/docker-config.component';
import { DockerService } from '../../docker/service/docker.service';
import { GolangConfigComponent } from '../../golang/config/golang-config.component';
import { GolangService } from '../../golang/service/golang.service';
import { MavenConfigComponent } from '../../maven/config/maven-config.component';
import { MavenService } from '../../maven/service/maven.service';
import { NpmConfigComponent } from '../../npm/config/npm-config.component';
import { NpmService } from '../../npm/service/npm.service';
import { PypiConfigComponent } from '../../pypi/config/pypi-config.component';
import { PypiService } from '../../pypi/service/pypi.service';
import { DeployTokenInfo } from './dto/deploy-token-info';
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
    DockerConfigComponent,
    CargoConfigComponent,
    GolangConfigComponent,
    NgClass,
    MavenConfigComponent,
    PypiConfigComponent,
    NpmConfigComponent,
    EllipsisPipe,
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
  public deployTokens: DeployTokenInfo[];
  public pagedData: PagedData<DeployTokenInfo>;
  public createdDeployToken: TokenCreateInfo;
  public showCreateTokenModal = false;
  public showTokenInfoModal = false;
  public showConfig = false;
  public selectedDeployToken: DeployTokenInfo;
  public repoUsage: RepoUsageInfo;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly golangService: GolangService,
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.pagedData = new PagedData<DeployTokenInfo>();
  }

  ngOnInit(): void {
    this.fetchDeployTokens();
  }

  private fetchRepoUsage() {
    this.fetchRepoUsageService().then((usage: RepoUsageInfo) => {
      this.repoUsage = usage;
    });
  }

  private fetchRepoUsageService(): Promise<RepoUsageInfo> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.getRepoUsage();
      }
      case RepoType.PYPI: {
        return this.pypiService.fetchRepositoryUsage();
      }
      case RepoType.DOCKER: {
        return this.dockerService.fetchRepositoryUsage();
      }
      case RepoType.NPM: {
        return this.npmService.fetchRegistryUsage();
      }
      case RepoType.CARGO: {
        return this.cargoService.fetchRepositoryUsage();
      }
      case RepoType.GOLANG: {
        return this.golangService.fetchRepositoryUsage();
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private fetchDeployTokensService(pageNum: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    this.fetchRepoUsage();
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.getDeployTokens(pageNum, pageSize);
      }
      case RepoType.PYPI: {
        return this.pypiService.getDeployTokens(pageNum, pageSize);
      }
      case RepoType.DOCKER: {
        return this.dockerService.getDeployTokens(pageNum, pageSize);
      }
      case RepoType.NPM: {
        return this.npmService.getDeployTokens(pageNum, pageSize);
      }
      case RepoType.GOLANG: {
        return this.golangService.getDeployTokens(pageNum, pageSize);
      }
      case RepoType.CARGO: {
        return this.cargoService.getDeployTokens(pageNum, pageSize);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  public fetchDeployTokens() {
    this.fetchDeployTokensService(this.pageNum, this.pageSize)
      .then((pageData: PagedData<DeployTokenInfo>) => {
        this.pagedData.page = pageData.page;
        this.deployTokens = pageData.content;
      })
      .catch(() => {
        /*User has no permission to view and manage. No actions, Message is written on UI*/
      });
  }

  public loadPage(pageNum: number) {
    this.pageNum = pageNum;
    this.fetchDeployTokens();
  }

  public createDeployToken(): void {
    this.showCreateTokenModal = true;
  }

  private rotateDeployTokenService(tokenUuid: string) {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.rotateDeployToken(tokenUuid);
      }
      case RepoType.PYPI: {
        return this.pypiService.rotateDeployToken(tokenUuid);
      }
      case RepoType.DOCKER: {
        return this.dockerService.rotateDeployToken(tokenUuid);
      }
      case RepoType.NPM: {
        return this.npmService.rotateDeployToken(tokenUuid);
      }
      case RepoType.CARGO: {
        return this.cargoService.rotateDeployToken(tokenUuid);
      }
      case RepoType.GOLANG: {
        return this.golangService.rotateDeployToken(tokenUuid);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  public rotateDeployToken(deployToken: DeployTokenInfo) {
    const successMsg = 'Deploy token rotated successfully';
    this.dangerModalService.show('Rotate Deploy Token', 'Rotate', () => {
      this.operationLock = true;

      this.rotateDeployTokenService(deployToken.id)
        .then((token: string) => {
          this.fetchDeployTokens();
          this.toastService.show(successMsg, 'success');
          this.createdDeployToken = new TokenCreateInfo();
          this.createdDeployToken.token = token;
          this.createdDeployToken.username = deployToken.username;
          this.createdDeployToken.id = deployToken.id;
          this.showTokenInfoModal = true;
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.operationLock = false;
        });
    });
  }

  private revokeDeployTokenService(tokenId: string) {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.revokeDeployToken(tokenId);
      }
      case RepoType.PYPI: {
        return this.pypiService.revokeDeployToken(tokenId);
      }
      case RepoType.DOCKER: {
        return this.dockerService.revokeDeployToken(tokenId);
      }
      case RepoType.NPM: {
        return this.npmService.revokeDeployToken(tokenId);
      }
      case RepoType.CARGO: {
        return this.cargoService.revokeDeployToken(tokenId);
      }
      case RepoType.GOLANG: {
        return this.golangService.revokeDeployToken(tokenId);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  public revokeDeployToken(deployToken: DeployTokenInfo) {
    const onSuccess = () => {
      this.fetchDeployTokens();
      this.toastService.show(successMsg, 'success');

      if (this.deployTokens.length === 1 && this.pageNum > 0) {
        this.pageNum = 0;
        this.fetchDeployTokens();
      }
    };

    const successMsg = 'Deploy token revoked successfully';
    this.dangerModalService.show('Delete Deploy Token', 'Delete', () => {
      this.operationLock = true;

      this.revokeDeployTokenService(deployToken.id)
        .then(onSuccess)
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.operationLock = false;
        });
    });
  }

  public configure(deployToken: DeployTokenInfo) {
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
