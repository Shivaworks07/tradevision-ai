import { Component, HostListener, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../services/auth.service';
import { CurrencyService } from '../../services/currency.service';

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.scss']
})
export class NavbarComponent {
  scrolled = false;
  auth     = inject(AuthService);
  currency = inject(CurrencyService);

  @HostListener('window:scroll')
  onScroll() { this.scrolled = window.scrollY > 40; }

  scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior:'smooth' }); }
  logout() { this.auth.logout(); }
}
