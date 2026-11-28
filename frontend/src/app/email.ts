const localPart = /^[a-z0-9!#$%&'*+/=?^_`{|}~-]+$/i;
const domainLabel = /^[a-z0-9-]+$/i;
const topLevelDomain = /^[a-z]{2,63}$/i;

export function validEmail(value: string): boolean {
  if (value.length > 254) return false;
  const at = value.indexOf('@');
  if (at < 1 || at > 64 || at !== value.lastIndexOf('@')) return false;
  if (!value.slice(0, at).split('.').every(part => localPart.test(part))) return false;
  const labels = value.slice(at + 1).split('.');
  return labels.length >= 2 && labels.every(label => label.length <= 63
    && !label.startsWith('-') && !label.endsWith('-') && domainLabel.test(label))
    && topLevelDomain.test(labels.at(-1) ?? '');
}
