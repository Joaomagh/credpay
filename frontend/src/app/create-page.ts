import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Creation } from './creation';
import { Icon } from './icon';
import { TransactionCard } from './transaction-card';

@Component({
  selector: 'cp-create', imports: [FormsModule, RouterLink, Icon, TransactionCard],
  templateUrl: './create-page.html'
})
export class CreatePage { readonly creation = inject(Creation); }
