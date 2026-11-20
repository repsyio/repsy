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
import { connectorCallbackUrl, parseConnectorRedirectUri } from './connector-redirect-uri';

describe('parseConnectorRedirectUri', () => {
  it('accepts an absolute https address and plain http on a loopback host', () => {
    expect(parseConnectorRedirectUri('https://mcp.repsy.io/oauth/upstream/callback')).toBe(
      'https://mcp.repsy.io/oauth/upstream/callback',
    );
    expect(parseConnectorRedirectUri('http://127.0.0.1:8765/cb')).toBe('http://127.0.0.1:8765/cb');
    expect(parseConnectorRedirectUri('http://localhost:8765/cb')).toBe('http://localhost:8765/cb');
  });

  it('refuses plain http elsewhere, other schemes, user info, a fragment and a value the parser rewrites', () => {
    for (const value of [
      null,
      '',
      'http://mcp.repsy.io/cb',
      'javascript:alert(1)',
      'https://user:pw@mcp.repsy.io/cb',
      'https://mcp.repsy.io/cb#x',
      'https://mcp.repsy.io/a/../cb',
      'https://MCP.repsy.io/cb',
      '/relative',
    ]) {
      expect(parseConnectorRedirectUri(value)).withContext(String(value)).toBeNull();
    }
  });
});

describe('connectorCallbackUrl', () => {
  it('adds the code and the state to the query, keeping what the address already has', () => {
    expect(connectorCallbackUrl('https://g.test/cb?x=1', 'c0de', 's t')).toBe(
      'https://g.test/cb?x=1&code=c0de&state=s+t',
    );
  });

  it('leaves the state out when the link had none', () => {
    expect(connectorCallbackUrl('https://g.test/cb', 'c0de', null)).toBe('https://g.test/cb?code=c0de');
  });
});
