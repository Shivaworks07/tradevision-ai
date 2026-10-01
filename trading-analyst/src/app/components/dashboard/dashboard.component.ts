import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';

@Component({ selector: 'app-dashboard', standalone: true, imports: [], template: '' })
export class DashboardComponent implements OnInit {
  private router = inject(Router);
  ngOnInit() { this.router.navigate(['/app/stocks']); }
}
