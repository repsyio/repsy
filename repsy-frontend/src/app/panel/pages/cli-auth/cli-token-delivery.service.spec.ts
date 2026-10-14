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
import { CliTokenDeliveryService } from './cli-token-delivery.service';

describe('CliTokenDeliveryService', () => {
  const service = new CliTokenDeliveryService();
  let submitted: { action: string; method: string; target: string; rel: string; fields: Record<string, string> }[];
  let onSubmit: () => void;

  beforeEach(() => {
    submitted = [];
    onSubmit = () => undefined;
    spyOn(HTMLFormElement.prototype, 'submit').and.callFake(function (this: HTMLFormElement) {
      const fields: Record<string, string> = {};
      new FormData(this).forEach((value, key) => (fields[key] = String(value)));
      submitted.push({ action: this.action, method: this.method, target: this.target, rel: this.rel, fields });
      onSubmit();
    });
  });

  it('posts the token and the state in a form body to the address, in a new tab without opener or referrer', async () => {
    const sent = await service.postToken('http://127.0.0.1:8123/callback', 'rut-fake', 'a1b2');

    expect(sent).toBeTrue();
    expect(submitted).toEqual([
      {
        action: 'http://127.0.0.1:8123/callback',
        method: 'post',
        target: '_blank',
        rel: 'noopener noreferrer',
        fields: { token: 'rut-fake', state: 'a1b2' },
      },
    ]);
  });

  it('keeps the state exactly as it came, odd characters included', async () => {
    const state = ' a&b=c%20d+é ';
    await service.postToken('http://[::1]:9/cb', 'rut-fake', state);

    expect(submitted[0].fields['state']).toBe(state);
  });

  it('leaves the state field out when the link had none', async () => {
    await service.postToken('http://127.0.0.1:8123/callback', 'rut-fake', null);

    expect(submitted[0].fields).toEqual({ token: 'rut-fake' });
  });

  it('does not leave the form, and so the secret, in the document', async () => {
    await service.postToken('http://127.0.0.1:8123/callback', 'rut-fake-secret', 's');

    expect(document.querySelector('form[action^="http://127.0.0.1"]')).toBeNull();
    expect(document.body.innerHTML).not.toContain('rut-fake-secret');
  });

  it('reports a form-action Content Security Policy violation as not sent', async () => {
    onSubmit = () => {
      setTimeout(() => {
        document.dispatchEvent(
          new SecurityPolicyViolationEvent('securitypolicyviolation', {
            violatedDirective: "form-action 'self' http://127.0.0.1:*",
          }),
        );
      });
    };

    expect(await service.postToken('http://[::1]:9/cb', 'rut-fake', 's')).toBeFalse();
  });

  it('ignores a violation of another directive', async () => {
    onSubmit = () => {
      setTimeout(() => {
        document.dispatchEvent(
          new SecurityPolicyViolationEvent('securitypolicyviolation', { violatedDirective: "img-src 'self'" }),
        );
      });
    };

    expect(await service.postToken('http://127.0.0.1:9/cb', 'rut-fake', 's')).toBeTrue();
  });
});
