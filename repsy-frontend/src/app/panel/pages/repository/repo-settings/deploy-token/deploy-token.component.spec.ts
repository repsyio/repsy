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

import moment from 'moment';
import { of, Subject, throwError } from 'rxjs';

import {
  ProtocolDeployTokenControllerService,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { renderComponent } from '../../testing/render-spec-helpers';
import { DeployTokenComponent } from './deploy-token.component';
import { DeployTokenInfo } from './dto/deploy-token-info';

const REPO = 'acme-repo';
const USAGE = { totalSize: 42 };

function token(id: string, username = `user-${id}`): DeployTokenInfo {
  return { id, username, name: id, read_only: false, created_at: '2026-01-01T00:00:00Z' };
}

function listing(tokens: DeployTokenInfo[], totalPages = 1): { data: unknown } {
  return { data: { content: tokens, page: { number: 0, size: 3, totalElements: tokens.length, totalPages } } };
}

describe('DeployTokenComponent', () => {
  let component: DeployTokenComponent;
  let tokenService: jasmine.SpyObj<ProtocolDeployTokenControllerService>;
  let repoService: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    tokenService = jasmine.createSpyObj<ProtocolDeployTokenControllerService>('ProtocolDeployTokenControllerService', [
      'listDeployTokens',
      'rotate',
      'revoke',
    ]);
    tokenService.listDeployTokens.and.returnValue(of(listing([token('a'), token('b')], 4)) as never);
    tokenService.rotate.and.returnValue(of({ data: 'new-secret' }) as never);
    tokenService.revoke.and.returnValue(of({}) as never);
    repoService = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getUsage']);
    repoService.getUsage.and.returnValue(of({ data: USAGE }) as never);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new DeployTokenComponent(tokenService, repoService, toastService, dangerModalService);
    component.activeRepository = permission(REPO, { canManage: true }) as RepoPermissionInfo;
    component.repoType = 'MAVEN';
  });

  describe('ngOnInit', () => {
    it('loads the first page of tokens, three at a time, and the usage of the repository', () => {
      component.ngOnInit();

      expect(tokenService.listDeployTokens).toHaveBeenCalledOnceWith({ page: 0, size: 3 }, REPO);
      expect(repoService.getUsage).toHaveBeenCalledOnceWith(REPO);
      expect(component.deployTokens.map((t) => t.id)).toEqual(['a', 'b']);
      expect(component.pagedData.page.totalPages).toBe(4);
      expect(component.repoUsage as unknown).toEqual(USAGE);
    });

    it('shows an empty list when the response has no content', () => {
      tokenService.listDeployTokens.and.returnValue(of({ data: {} }) as never);

      component.ngOnInit();

      expect(component.deployTokens).toEqual([]);
    });

    it('keeps what it has when the tokens or the usage cannot be loaded', () => {
      tokenService.listDeployTokens.and.returnValue(throwError(() => new Error('boom')));
      repoService.getUsage.and.returnValue(throwError(() => new Error('boom')));

      component.ngOnInit();

      expect(component.deployTokens).toBeUndefined();
      expect(component.repoUsage).toBeUndefined();
    });
  });

  describe('loadPage', () => {
    it('fetches the requested page', () => {
      component.loadPage(2);

      expect(component.pageNum).toBe(2);
      expect(tokenService.listDeployTokens).toHaveBeenCalledOnceWith({ page: 2, size: 3 }, REPO);
    });
  });

  describe('createDeployToken', () => {
    it('opens the create modal', () => {
      component.createDeployToken();

      expect(component.showCreateTokenModal).toBeTrue();
    });
  });

  describe('rotateDeployToken', () => {
    const rotated = token('a', 'deployer');

    it('asks for confirmation before rotating anything', () => {
      component.rotateDeployToken(rotated);

      expect(dangerModalService.modal).toEqual({ title: 'Rotate Deploy Token', action: 'Rotate', message: null });
      expect(tokenService.rotate).not.toHaveBeenCalled();
    });

    it('rotates once confirmed, reloads, toasts and shows the new secret', () => {
      component.rotateDeployToken(rotated);
      tokenService.listDeployTokens.calls.reset();

      dangerModalService.call();

      expect(tokenService.rotate).toHaveBeenCalledOnceWith('a', REPO);
      expect(tokenService.listDeployTokens).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Deploy token rotated successfully', 'success');
      expect(component.createdDeployToken).toEqual(
        jasmine.objectContaining({ token: 'new-secret', username: 'deployer', id: 'a' }),
      );
      expect(component.showTokenInfoModal).toBeTrue();
      expect(component.operationLock).toBeFalse();
    });

    it('holds the operation lock while the rotation is running', () => {
      const answer = new Subject<{ data: string }>();
      tokenService.rotate.and.returnValue(answer as never);
      component.rotateDeployToken(rotated);

      dangerModalService.call();
      expect(component.operationLock).toBeTrue();

      answer.next({ data: 'new-secret' });
      answer.complete();
      expect(component.operationLock).toBeFalse();
    });

    it('shows nothing and releases the lock when the rotation fails', () => {
      tokenService.rotate.and.returnValue(throwError(() => new Error('boom')));
      component.rotateDeployToken(rotated);

      dangerModalService.call();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.showTokenInfoModal).toBeFalse();
      expect(component.createdDeployToken).toBeUndefined();
      expect(component.operationLock).toBeFalse();
    });
  });

  describe('revokeDeployToken', () => {
    beforeEach(() => component.ngOnInit());

    it('asks for confirmation before revoking anything', () => {
      component.revokeDeployToken(token('a'));

      expect(dangerModalService.modal).toEqual({ title: 'Delete Deploy Token', action: 'Delete', message: null });
      expect(tokenService.revoke).not.toHaveBeenCalled();
    });

    it('revokes once confirmed, then reloads and toasts', () => {
      component.revokeDeployToken(token('a'));
      tokenService.listDeployTokens.calls.reset();

      dangerModalService.call();

      expect(tokenService.revoke).toHaveBeenCalledOnceWith('a', REPO);
      expect(tokenService.listDeployTokens).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Deploy token revoked successfully', 'success');
      expect(component.operationLock).toBeFalse();
    });

    it('goes back to the first page when the last token of a later page is revoked', () => {
      tokenService.listDeployTokens.and.returnValue(of(listing([token('only')], 2)) as never);
      component.loadPage(1);
      component.revokeDeployToken(token('only'));
      tokenService.listDeployTokens.calls.reset();

      dangerModalService.call();

      expect(component.pageNum).toBe(0);
      expect(tokenService.listDeployTokens.calls.allArgs()).toEqual([
        [{ page: 1, size: 3 }, REPO],
        [{ page: 0, size: 3 }, REPO],
      ]);
    });

    it('stays on the page when other tokens remain on it, or when it is the first page', () => {
      tokenService.listDeployTokens.and.returnValue(of(listing([token('only')], 1)) as never);
      component.loadPage(0);
      component.revokeDeployToken(token('only'));
      tokenService.listDeployTokens.calls.reset();

      dangerModalService.call();

      expect(tokenService.listDeployTokens).toHaveBeenCalledTimes(1);
    });

    it('neither reloads nor toasts, and releases the lock, when the revoke fails', () => {
      tokenService.revoke.and.returnValue(throwError(() => new Error('boom')));
      component.revokeDeployToken(token('a'));
      tokenService.listDeployTokens.calls.reset();

      dangerModalService.call();

      expect(tokenService.listDeployTokens).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.operationLock).toBeFalse();
    });
  });

  describe('configure', () => {
    it('shows the connection config of the chosen token, and can close it', () => {
      const chosen = token('a');

      component.configure(chosen);
      expect(component.selectedDeployToken).toBe(chosen);
      expect(component.showConfig).toBeTrue();

      component.openConfigure(false);
      expect(component.showConfig).toBeFalse();
    });
  });

  describe('getBorderForExpireStatus', () => {
    const normal = 'text-[#FDFDFD]';

    it('is the normal colour when the token never expires', () => {
      expect(component.getBorderForExpireStatus(undefined as never)).toBe(normal);
      expect(component.getBorderForExpireStatus('')).toBe(normal);
    });

    it('is the error colour once the token has expired', () => {
      expect(component.getBorderForExpireStatus(moment().subtract(1, 'minute').toISOString())).toBe('text-error-500');
      expect(component.getBorderForExpireStatus(moment().subtract(30, 'days').toISOString())).toBe('text-error-500');
    });

    it('is the warning colour within the last week before it expires', () => {
      expect(component.getBorderForExpireStatus(moment().add(1, 'day').toISOString())).toBe('text-warning-500');
      expect(component.getBorderForExpireStatus(moment().add(7, 'days').endOf('day').toISOString())).toBe(
        'text-warning-500',
      );
    });

    it('is the normal colour when the token has more than a week left', () => {
      expect(component.getBorderForExpireStatus(moment().add(8, 'days').toISOString())).toBe(normal);
      expect(component.getBorderForExpireStatus(moment().add(1, 'year').toISOString())).toBe(normal);
    });
  });

  describe('timeAgo', () => {
    it('renders a relative time', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toDate())).toBe('3 days ago');
    });
  });
});

describe('DeployTokenComponent template', () => {
  async function render(canManage: boolean): Promise<HTMLElement> {
    const tokenService = jasmine.createSpyObj<ProtocolDeployTokenControllerService>(
      'ProtocolDeployTokenControllerService',
      ['listDeployTokens'],
    );
    tokenService.listDeployTokens.and.returnValue(of(listing([token('a')])) as never);
    const repoService = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getUsage',
    ]);
    repoService.getUsage.and.returnValue(of({ data: USAGE }) as never);

    const { el } = await renderComponent(
      DeployTokenComponent,
      [
        { provide: ProtocolDeployTokenControllerService, useValue: tokenService },
        { provide: ProtocolRepoControllerService, useValue: repoService },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
      { activeRepository: permission(REPO, { canManage }), repoType: 'MAVEN' },
    );
    return el;
  }

  it('offers Create Token to a repository manager', async () => {
    const el = await render(true);

    expect(el.querySelector('[data-testid="token-create"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="token-table"]')).not.toBeNull();
  });

  it('offers neither the Create Token button nor the token list to anyone else (RPS-1262)', async () => {
    const el = await render(false);

    expect(el.querySelector('[data-testid="token-create"]')).toBeNull();
    expect(el.querySelector('[data-testid="token-table"]')).toBeNull();
  });
});
