export interface Client { id: string; nome: string }
export interface ClientPage { itens: Client[]; proximo: string | null }

export function normalizeClientName(value: string): string {
  const invalid = () => new Error('Informe um nome de 1 a 120 caracteres, sem controles.');
  if (/[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value)) throw invalid();
  const characters = Array.from(value);
  let start = 0;
  let end = characters.length;
  while (start < end && /^\p{White_Space}$/u.test(characters[start])) start++;
  while (end > start && /^\p{White_Space}$/u.test(characters[end - 1])) end--;
  if (end - start < 1 || end - start > 120) throw invalid();
  return characters.slice(start, end).join('');
}
