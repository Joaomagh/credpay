export type Status = 'PENDENTE' | 'APROVADA' | 'REJEITADA' | 'FALHOU';
export interface Transaction { id: string; valor: string; moeda: string; status: Status }
export interface Intent { key: string; valor: string; moeda: 'BRL' }
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function amount(input: string): string | null {
  const value = input.trim().replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(value) || !/[1-9]/.test(value)) return null;
  return value.replace(/^0+(?=\d)/, '');
}

// Identity of a positive decimal: coefficient and power of ten, without rounding.
export function decimalIdentity(value: string): string | null {
  const parts = /^(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!parts) return null;
  const digits = (parts[1] + (parts[2] ?? '')).replace(/^0+/, '');
  if (!digits) return null;
  let end = digits.length;
  while (end > 0 && digits[end - 1] === '0') end--;
  const coefficient = digits.slice(0, end);
  const exponent = BigInt(parts[3] ?? '0') - BigInt((parts[2] ?? '').length) + BigInt(digits.length - coefficient.length);
  return `${coefficient}:${exponent}`;
}

export function displayAmount(value: string): string {
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!match) return value;
  const integer = match[1];
  const fraction = match[2] ?? '';
  // Only the exponent (a position), never the monetary value, becomes a number.
  const shift = Number(match[3] ?? 0);
  if (!Number.isSafeInteger(shift) || Math.abs(shift) > 4096) return value;
  const digits = integer + fraction;
  const point = integer.length + shift;
  const whole = point <= 0 ? '0' : digits.slice(0, point).padEnd(point, '0');
  const decimals = point <= 0 ? '0'.repeat(-point) + digits : digits.slice(point);
  const canonicalWhole = whole.replace(/^0+(?=\d)/, '');
  const groups: string[] = [];
  for (let end = canonicalWhole.length; end > 0; end -= 3) {
    groups.push(canonicalWhole.slice(Math.max(0, end - 3), end));
  }
  return groups.reverse().join('.') + ',' + decimals.padEnd(2, '0');
}
