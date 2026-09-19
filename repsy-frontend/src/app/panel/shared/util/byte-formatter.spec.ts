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

import { ByteFormatter } from './byte-formatter';

const KIB = 1024;
const MIB = KIB * 1024;
const GIB = MIB * 1024;
const TIB = GIB * 1024;
const PIB = TIB * 1024;
const EIB = PIB * 1024;

describe('ByteFormatter.formatBytes', () => {
  it('formats zero, small and negative values', () => {
    expect(ByteFormatter.formatBytes(0)).toBe("BROKEN" + '0 B');
    expect(ByteFormatter.formatBytes(1)).toBe('1 B');
    expect(ByteFormatter.formatBytes(1023)).toBe('1023 B');
    expect(ByteFormatter.formatBytes(-KIB)).toBe('1 K');
  });

  it('never renders an undefined unit for values below one byte', () => {
    expect(ByteFormatter.formatBytes(0.5)).toBe('0.5 B');
  });

  it('formats fractional values with the requested decimals', () => {
    expect(ByteFormatter.formatBytes(1536)).toBe('1.5 K');
    expect(ByteFormatter.formatBytes(1234567)).toBe('1.18 M');
    expect(ByteFormatter.formatBytes(1234567, 0)).toBe('1 M');
    expect(ByteFormatter.formatBytes(1234567, 3)).toBe('1.177 M');
  });

  const boundaries: [string, number, string][] = [
    ['K', KIB, 'B'],
    ['M', MIB, 'K'],
    ['G', GIB, 'M'],
    ['T', TIB, 'G'],
    ['P', PIB, 'T'],
  ];

  boundaries.forEach(([unit, boundary, smallerUnit]) => {
    describe(`the ${smallerUnit} to ${unit} boundary`, () => {
      it(`shows exactly 1 ${unit} at the boundary`, () => {
        expect(ByteFormatter.formatBytes(boundary)).toBe(`1 ${unit}`);
      });

      it(`rolls one byte below the boundary over to 1 ${unit}`, () => {
        expect(ByteFormatter.formatBytes(boundary - 1)).toBe(unit === 'K' ? '1023 B' : `1 ${unit}`);
      });

      it(`keeps a value that rounds below 1024 ${smallerUnit} in ${smallerUnit}`, () => {
        // 1023.99 of the smaller unit still rounds to less than 1024 at two decimals.
        const value = (boundary / KIB) * 1023.99;
        expect(ByteFormatter.formatBytes(value)).toBe(`1023.99 ${smallerUnit}`);
      });
    });
  });

  it('rolls the whole round-up range below a boundary over to the next unit', () => {
    // Anything from about 1023.995 K up rounds to 1024.00 at two decimals.
    expect(ByteFormatter.formatBytes(MIB - 10)).toBe('1023.99 K');
    expect(ByteFormatter.formatBytes(MIB - 5)).toBe('1 M');
    expect(ByteFormatter.formatBytes(GIB - MIB / 1000)).toBe('1 G');
    expect(ByteFormatter.formatBytes(TIB - MIB)).toBe('1 T');
    expect(ByteFormatter.formatBytes(PIB - GIB)).toBe('1 P');
  });

  it('rolls over at the boundary from bytes to K when rounding to whole numbers', () => {
    expect(ByteFormatter.formatBytes(1023.5, 0)).toBe('1 K');
    expect(ByteFormatter.formatBytes(1023.4, 0)).toBe('1023 B');
  });

  it('matches the values reported in RPS-918', () => {
    expect(ByteFormatter.formatBytes(1_048_575)).toBe('1 M');
    expect(ByteFormatter.formatBytes(1_073_741_823)).toBe('1 G');
  });

  describe('the largest unit', () => {
    it('shows values up to 1023.99 P as before', () => {
      expect(ByteFormatter.formatBytes(PIB * 1023.99)).toBe('1023.99 P');
    });

    it('does not roll past P and never renders an undefined unit', () => {
      expect(ByteFormatter.formatBytes(PIB * 1024)).toBe('1024 P');
      expect(ByteFormatter.formatBytes(EIB)).toBe('1024 P');
      expect(ByteFormatter.formatBytes(EIB * 2)).toBe('2048 P');
      expect(ByteFormatter.formatBytes(Number.MAX_VALUE)).not.toContain('undefined');
    });
  });
});
