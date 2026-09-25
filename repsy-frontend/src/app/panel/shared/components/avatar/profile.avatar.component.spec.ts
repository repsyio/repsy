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
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProfileAvatarComponent } from './profile.avatar.component';

// RPS-1402: the avatar used to load a Gravatar image (sending the email hash to a third party).
describe('ProfileAvatarComponent', () => {
  let fixture: ComponentFixture<ProfileAvatarComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ProfileAvatarComponent] });
    fixture = TestBed.createComponent(ProfileAvatarComponent);
  });

  it('shows the given initial and requests no image', () => {
    fixture.componentRef.setInput('fallbackChar', 'A');
    fixture.componentRef.setInput('size', 45);
    fixture.detectChanges();

    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('[data-testid="avatar-fallback"]')?.textContent?.trim()).toBe('A');
    expect((root.querySelector('[data-testid="avatar"]') as HTMLElement).style.width).toBe('45px');
  });

  it('falls back to a question mark without an initial', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="avatar-fallback"]')?.textContent?.trim()).toBe('?');
  });
});
