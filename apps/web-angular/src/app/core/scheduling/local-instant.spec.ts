import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { localDateTimeToInstant } from './local-instant';

// The test runner is Node; the timezone is switched per describe block so results never depend on the developer's machine.
const env = (globalThis as unknown as { process: { env: Record<string, string | undefined> } }).process.env;

describe('localDateTimeToInstant', () => {
  const original = env['TZ'];

  afterAll(() => {
    if (original === undefined) delete env['TZ'];
    else env['TZ'] = original;
  });

  describe('Europe/Bucharest (EET UTC+2, EEST UTC+3; DST change 2026-03-29 03:00 and 2026-10-25 04:00)', () => {
    beforeAll(() => {
      env['TZ'] = 'Europe/Bucharest';
    });

    it('applies winter and summer offsets either side of the spring-forward change', () => {
      expect(localDateTimeToInstant('2026-03-28T12:00')).toBe('2026-03-28T10:00:00.000Z');
      expect(localDateTimeToInstant('2026-03-30T12:00')).toBe('2026-03-30T09:00:00.000Z');
    });

    it('moves a wall-clock time that does not exist forward instead of failing', () => {
      // 03:30 never happens on 2026-03-29 in Bucharest; the browser resolves it to 04:30 EEST = 01:30Z.
      const instant = localDateTimeToInstant('2026-03-29T03:30');
      expect(instant).toBe('2026-03-29T01:30:00.000Z');
    });

    it('resolves the repeated hour after the fall-back change to a single instant', () => {
      const instant = localDateTimeToInstant('2026-10-25T03:30');
      expect(['2026-10-25T00:30:00.000Z', '2026-10-25T01:30:00.000Z']).toContain(instant);
      expect(localDateTimeToInstant('2026-10-25T05:00')).toBe('2026-10-25T03:00:00.000Z');
    });
  });

  describe('America/New_York (EST UTC-5, EDT UTC-4; DST change 2026-03-08 02:00)', () => {
    beforeAll(() => {
      env['TZ'] = 'America/New_York';
    });

    it('is independent of the developer machine timezone', () => {
      expect(localDateTimeToInstant('2026-03-07T12:00')).toBe('2026-03-07T17:00:00.000Z');
      expect(localDateTimeToInstant('2026-03-09T12:00')).toBe('2026-03-09T16:00:00.000Z');
    });
  });

  it('rejects empty and malformed input', () => {
    expect(localDateTimeToInstant('')).toBeNull();
    expect(localDateTimeToInstant('not a date')).toBeNull();
  });

  it('always produces a UTC ISO instant', () => {
    env['TZ'] = 'Asia/Tokyo';
    expect(localDateTimeToInstant('2026-06-01T09:00')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(localDateTimeToInstant('2026-06-01T09:00')).toBe('2026-06-01T00:00:00.000Z');
  });
});
