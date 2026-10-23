import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Intent, Transaction, UUID, amount } from './transaction';

const JOURNAL_ENTRY = 'credpay.creation.v1';
export interface SavedCreation { version: 1; intent: Intent; receipt: Transaction | null }
export class InvalidCreationRecord extends Error {}

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

// Identity of a positive decimal: coefficient and power of ten, without rounding.
function decimalIdentity(value: string): string | null {
  const parts = /^(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!parts) return null;
  const digits = (parts[1] + (parts[2] ?? '')).replace(/^0+/, '');
  if (!digits) return null;
  const coefficient = digits.replace(/0+$/, '');
  const exponent = BigInt(parts[3] ?? '0') - BigInt((parts[2] ?? '').length) + BigInt(digits.length - coefficient.length);
  return `${coefficient}:${exponent}`;
}

function validate(value: unknown): SavedCreation {
  if (!record(value) || value['version'] !== 1 || !record(value['intent'])) throw new InvalidCreationRecord();
  const intent = value['intent'];
  if (typeof intent['key'] !== 'string' || !UUID.test(intent['key'])
      || typeof intent['valor'] !== 'string' || amount(intent['valor']) !== intent['valor']
      || intent['moeda'] !== 'BRL') throw new InvalidCreationRecord();
  const savedIntent: Intent = {key: intent['key'], valor: intent['valor'], moeda: 'BRL'};
  const receipt = value['receipt'];
  if (receipt === null) return {version: 1, intent: savedIntent, receipt: null};
  if (!record(receipt) || typeof receipt['id'] !== 'string' || !UUID.test(receipt['id'])
      || typeof receipt['valor'] !== 'string' || receipt['moeda'] !== 'BRL' || receipt['status'] !== 'PENDENTE'
      || decimalIdentity(receipt['valor']) !== decimalIdentity(savedIntent.valor)) throw new InvalidCreationRecord();
  return {version: 1, intent: savedIntent, receipt: {id: receipt['id'], valor: receipt['valor'], moeda: 'BRL', status: 'PENDENTE'}};
}

@Injectable({providedIn: 'root'})
export class CreationJournal {
  private readonly document = inject(DOCUMENT);
  private storage(): Storage {
    const view = this.document.defaultView;
    if (!view) throw new Error('Storage unavailable');
    return view.sessionStorage;
  }
  load(): SavedCreation | null {
    const raw = this.storage().getItem(JOURNAL_ENTRY);
    if (raw === null) return null;
    try {
      if (raw.length > 65536) throw new InvalidCreationRecord();
      return validate(JSON.parse(raw) as unknown);
    } catch { throw new InvalidCreationRecord(); }
  }
  save(intent: Intent, receipt: Transaction | null): void {
    const saved = validate({version: 1, intent, receipt});
    const raw = JSON.stringify(saved);
    if (raw.length > 65536) throw new Error('Storage record too large');
    this.storage().setItem(JOURNAL_ENTRY, raw);
  }
  clear(): void { this.storage().removeItem(JOURNAL_ENTRY); }
}
