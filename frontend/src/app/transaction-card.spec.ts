import { TestBed } from '@angular/core/testing';
import { TransactionCard } from './transaction-card';

describe('Moeda do resultado consultado', () => {
  it('apresenta UUID como texto e mantém nome acessível no botão de copiar', () => {
    const fixture = TestBed.createComponent(TransactionCard);
    const id = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
    fixture.componentRef.setInput('transaction', {id, valor: '250', moeda: 'BRL', status: 'APROVADA'});
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(Array.from(root.querySelectorAll('label')).filter(label => !label.control)).toHaveLength(0);
    expect(root.querySelector('code')?.textContent).toBe(id);
    expect(root.querySelector('button')?.getAttribute('aria-label')).toBe('Copiar identificador da transação');
  });
  it('exibe USD sem rotular o valor como real brasileiro', () => {
    const fixture = TestBed.createComponent(TransactionCard);
    fixture.componentRef.setInput('transaction', {id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', valor: '250', moeda: 'USD', status: 'APROVADA'});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('USD');
    expect(fixture.nativeElement.textContent).not.toContain('R$');
    expect(fixture.nativeElement.textContent).not.toContain('Real brasileiro');
  });
});
