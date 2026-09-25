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

import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { ToastService } from '../components/toast/toast.service';
import {
  deleteVersionAndCheckLast$,
  isLastVersion,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
} from './version-delete-landing.util';

describe('version delete landing (RPS-1288)', () => {
  describe('isLastVersion', () => {
    it('is true for a package with one version, and for one with none', () => {
      expect(isLastVersion({ content: [{ v: '1' }] })).toBeTrue();
      expect(isLastVersion({ content: [] })).toBeTrue();
    });

    it('is false as soon as a second version exists', () => {
      expect(isLastVersion({ content: [{ v: '1' }, { v: '2' }] })).toBeFalse();
    });

    it('reads a probe page big enough to tell the two apart', () => {
      expect(VERSION_PROBE_SIZE).toBeGreaterThanOrEqual(2);
    });
  });

  describe('deleteVersionAndCheckLast$', () => {
    it('deletes after the probe answered and says whether it was the last version', () => {
      const probe = new Subject<{ content: unknown[] }>();
      const remove = new Subject<void>();
      const startedDelete = jasmine.createSpy('startedDelete');
      const results: boolean[] = [];

      deleteVersionAndCheckLast$(probe, () => {
        startedDelete();
        return remove;
      }).subscribe((wasLast) => results.push(wasLast));

      expect(startedDelete).not.toHaveBeenCalled();
      probe.next({ content: [{}] });
      expect(startedDelete).toHaveBeenCalledTimes(1);
      expect(results).toEqual([]);
      remove.next();
      expect(results).toEqual([true]);
    });

    it('says "not the last" when other versions remain', () => {
      const results: boolean[] = [];

      deleteVersionAndCheckLast$(of({ content: [{}, {}] }), () => of(undefined)).subscribe((wasLast) =>
        results.push(wasLast),
      );

      expect(results).toEqual([false]);
    });

    it('never deletes when the probe fails', () => {
      const startedDelete = jasmine.createSpy('startedDelete');
      const errors: unknown[] = [];

      deleteVersionAndCheckLast$(
        throwError(() => 'no versions'),
        () => {
          startedDelete();
          return of(undefined);
        },
      ).subscribe({ error: (e) => errors.push(e) });

      expect(errors).toEqual(['no versions']);
      expect(startedDelete).not.toHaveBeenCalled();
    });

    it('passes a failed delete on', () => {
      const errors: unknown[] = [];

      deleteVersionAndCheckLast$(of({ content: [{}] }), () => throwError(() => 'locked')).subscribe({
        error: (e) => errors.push(e),
      });

      expect(errors).toEqual(['locked']);
    });
  });

  describe('landAfterVersionDelete', () => {
    let router: jasmine.SpyObj<Router>;
    let toast: jasmine.SpyObj<ToastService>;
    const route = {} as ActivatedRoute;

    beforeEach(() => {
      router = jasmine.createSpyObj<Router>('Router', ['navigate']);
      router.navigate.and.resolveTo(true);
      toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    });

    it('goes up to the versions page of the package, then toasts', async () => {
      const landed = landAfterVersionDelete(router, route, toast, 'my-repo', false);

      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(toast.show).not.toHaveBeenCalled();
      await landed;
      expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('goes to the package list of the repository after the last version, then toasts', async () => {
      await landAfterVersionDelete(router, route, toast, 'my-repo', true);

      expect(router.navigate).toHaveBeenCalledOnceWith(['/', 'my-repo']);
      expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });
  });
});
