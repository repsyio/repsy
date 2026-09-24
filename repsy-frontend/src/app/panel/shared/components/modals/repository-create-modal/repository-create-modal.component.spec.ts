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

import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { RepoCollectionControllerService, RepoType as ApiRepoType } from '../../../../../../generated/api';
import { RepoType } from '../../../dto/repo/repo-type';
import { ToastService } from '../../toast/toast.service';
import { RepositoryCreateModalComponent } from './repository-create-modal.component';

describe('RepositoryCreateModalComponent name validation', () => {
  let component: RepositoryCreateModalComponent;

  beforeEach(() => {
    component = new RepositoryCreateModalComponent(
      {} as RepoCollectionControllerService,
      new FormBuilder(),
      {} as Router,
      {} as ToastService,
    );
    component.ngOnInit();
  });

  const isValid = (name: string): boolean => {
    component.form.get('name').setValue(name);
    return component.form.get('name').valid;
  };

  it('accepts letters, digits, underscore and inner hyphens', () => {
    expect(isValid('my-repo_1')).toBeTrue();
    expect(isValid('_private')).toBeTrue();
    expect(isValid('9lives')).toBeTrue();
  });

  it('rejects a name that starts with a hyphen', () => {
    expect(isValid('-repo')).toBeFalse();
    expect(component.form.get('name').errors?.['pattern']).toBeTruthy();
  });

  it('rejects an empty name and other characters', () => {
    expect(isValid('')).toBeFalse();
    expect(isValid('my repo')).toBeFalse();
    expect(isValid('my@repo')).toBeFalse();
  });

  // RPS-1158: reserved names collide with the panel's fixed top-level routes.
  it('rejects a name reserved by the panel routes, case-insensitively', () => {
    expect(isValid('login')).toBeFalse();
    expect(component.form.get('name').errors?.['reservedName']).toBeTruthy();

    expect(isValid('Repositories')).toBeFalse();
    expect(component.form.get('name').errors?.['reservedName']).toBeTruthy();

    expect(isValid('FAVICON.ICO')).toBeFalse();
    expect(component.form.get('name').errors?.['reservedName']).toBeTruthy();
  });

  it('accepts a name that is not reserved', () => {
    expect(isValid('login-service')).toBeTrue();
  });
});

// RPS-1267: Cancel sits inside the form, so without type="button" it also submits it.
describe('RepositoryCreateModalComponent buttons', () => {
  const form = (root: HTMLElement) => root.querySelector('[data-testid="repo-create-form"]') as HTMLFormElement;

  const open = () => {
    TestBed.configureTestingModule({
      imports: [RepositoryCreateModalComponent],
      providers: [
        { provide: RepoCollectionControllerService, useValue: {} },
        { provide: Router, useValue: {} },
        { provide: ToastService, useValue: {} },
      ],
    });
    const fixture = TestBed.createComponent(RepositoryCreateModalComponent);
    fixture.componentInstance.open = true;
    fixture.detectChanges();
    return fixture;
  };

  it('makes Cancel a plain button and Create the submit button', () => {
    const root: HTMLElement = open().nativeElement;

    expect(root.querySelector('[data-testid="repo-create-cancel"]')?.getAttribute('type')).toBe('button');
    expect(root.querySelector('[data-testid="repo-create-submit"]')?.getAttribute('type')).toBe('submit');
  });

  it('closes on Cancel without submitting the form', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    const submitted = jasmine.createSpy('submit').and.callFake((event: Event) => event.preventDefault());
    form(root).addEventListener('submit', submitted);
    const closed = jasmine.createSpy('closed');
    fixture.componentInstance.openChange.subscribe(closed);

    (root.querySelector('[data-testid="repo-create-cancel"]') as HTMLButtonElement).click();

    expect(closed).toHaveBeenCalledOnceWith(false);
    expect(submitted).not.toHaveBeenCalled();
  });

  it('creates once, through the form submit, when Enter is pressed in a valid name field', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    const create = spyOn(fixture.componentInstance, 'createRepo');
    fixture.componentInstance.form.get('name')?.setValue('my-repo');
    fixture.detectChanges();

    form(root).dispatchEvent(new Event('submit', { cancelable: true }));

    expect(create).toHaveBeenCalledTimes(1);
    const name = root.querySelector('[data-testid="repo-create-name"]') as HTMLInputElement;
    name.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(create).toHaveBeenCalledTimes(1);
  });
});

describe('RepositoryCreateModalComponent create', () => {
  let api: jasmine.SpyObj<RepoCollectionControllerService>;
  let router: jasmine.SpyObj<Router>;
  let toast: jasmine.SpyObj<ToastService>;
  let component: RepositoryCreateModalComponent;
  let created: unknown[];
  let closed: boolean[];

  beforeEach(() => {
    api = jasmine.createSpyObj<RepoCollectionControllerService>('RepoCollectionControllerService', [
      'createRepository',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.returnValue(Promise.resolve(true));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    component = new RepositoryCreateModalComponent(api, new FormBuilder(), router, toast);
    created = [];
    closed = [];
    component.created.subscribe((repo) => created.push(repo));
    component.openChange.subscribe((open) => closed.push(open));
    component.selectedOption = RepoType.NPM;
    component.ngOnInit();
    component.form.patchValue({ name: 'my-repo', privateRepo: false, description: 'a description' });
  });

  it('posts ONE request with the upper-case type in the body, next to the form fields', () => {
    api.createRepository.and.returnValue(of({ data: { name: 'my-repo' } }) as never);

    component.createRepo();

    expect(api.createRepository).toHaveBeenCalledOnceWith({
      name: 'my-repo',
      privateRepo: false,
      description: 'a description',
      type: ApiRepoType.Npm,
    });
  });

  it('sends the type the user picked in the selector', () => {
    api.createRepository.and.returnValue(of({ data: { name: 'my-repo' } }) as never);
    component.selectOption(RepoType.GOLANG);

    component.createRepo();

    expect(api.createRepository.calls.mostRecent().args[0].type).toBe(ApiRepoType.Golang);
  });

  it('emits the created repository the server returned, closes the modal and toasts', async () => {
    const item = { name: 'my-repo', type: ApiRepoType.Npm };
    api.createRepository.and.returnValue(of({ data: item }) as never);

    component.createRepo();
    await Promise.resolve();

    expect(created).toEqual([item]);
    expect(closed).toEqual([false]);
    expect(router.navigate).toHaveBeenCalledOnceWith(['/repositories']);
    expect(toast.show).toHaveBeenCalledOnceWith('Repository created successfully', 'success');
    expect(component.loading).toBeFalse();
    expect(component.form.enabled).toBeTrue();
  });

  for (const status of [409, 400]) {
    it(`emits nothing and stays open when the server answers ${status} (the interceptor shows the message)`, () => {
      api.createRepository.and.returnValue(throwError(() => ({ status })));

      component.createRepo();

      expect(created).toEqual([]);
      expect(closed).toEqual([]);
      expect(toast.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
      expect(component.form.enabled).toBeTrue();
      expect(component.form.get('name')?.value).toBe('my-repo');
    });
  }
});
