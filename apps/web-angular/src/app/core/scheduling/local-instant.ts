/**
 * Converts a `datetime-local` value (no timezone) into an unambiguous UTC instant.
 *
 * The browser parses the value as wall-clock time in the operator's own timezone and `toISOString()` converts it to UTC, which is the
 * only form the API stores or accepts. The conversion is therefore DST-correct by construction: a wall-clock time that does not exist
 * (spring forward) is moved forward by the browser, and an ambiguous one (fall back) resolves to its first occurrence.
 */
export function localDateTimeToInstant(localDateTime: string): string | null {
  if (!localDateTime) {
    return null;
  }
  const parsed = new Date(localDateTime);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed.toISOString();
}
