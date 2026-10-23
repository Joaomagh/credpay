import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Icon } from './icon';

@Component({
  selector: 'cp-app', imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  template: `
    <a class="skip-link" href="#main">Ir para o conteúdo</a>
    <header class="app-header"><a class="brand" routerLink="/nova" aria-label="CredPay · início"><span class="brand-mark">C</span><span>Cred<span class="brand-accent">Pay</span></span></a><div class="header-end"><span class="demo-badge">Demo</span><span class="header-caption">Demonstração · dados fictícios</span></div></header>
    <div class="app-layout"><aside class="sidebar"><span class="eyebrow nav-caption">Workspace</span><nav aria-label="Navegação principal"><a routerLink="/nova" routerLinkActive="active" ariaCurrentWhenActive="page"><cp-icon name="plus" /><span>Nova transação</span></a><a routerLink="/consultar" routerLinkActive="active" ariaCurrentWhenActive="page"><cp-icon name="search" /><span>Consultar</span></a></nav><p class="mobile-disclaimer">Demonstração · dados fictícios</p><div class="sidebar-note"><span class="dot"></span><div><strong>Ambiente de demonstração</strong><p>Java · RabbitMQ · Angular</p></div></div></aside>
    <main id="main" tabindex="-1"><router-outlet /></main></div>
    <footer><cp-icon name="shield" /><span>Ambiente de demonstração — dados fictícios. Sem movimentação financeira real.</span><span class="footer-label">CREDPAY / CONSOLE</span></footer>
  `
})
export class App {}
