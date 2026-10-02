import { Component, Input, Output, EventEmitter } from '@angular/core';


@Component({
    selector: 'tv-empty',
    imports: [],
    template: `
    <div class="empty-state">
      <div class="es-icon">{{icon}}</div>
      <div class="es-title">{{title}}</div>
      <div class="es-desc">{{description}}</div>
      @if (actionLabel) {
        <button class="es-btn" (click)="onAction.emit()">{{actionLabel}}</button>
      }
    </div>
    `,
    styles: [`
    .empty-state{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:48px 24px;text-align:center;gap:8px;}
    .es-icon{font-size:40px;margin-bottom:4px;opacity:0.6;}
    .es-title{font-size:16px;font-weight:700;color:#E8EDF5;}
    .es-desc{font-size:12px;color:#4A5568;max-width:280px;line-height:1.6;}
    .es-btn{margin-top:10px;padding:8px 20px;border-radius:9px;border:1px solid #2A3F6A;background:rgba(0,212,255,0.08);color:#00D4FF;font-family:'Space Grotesk',sans-serif;font-size:12px;font-weight:700;cursor:pointer;transition:all 0.15s;&:hover{background:rgba(0,212,255,0.15);}}
  `]
})
export class EmptyStateComponent {
  @Input() icon        = '📊';
  @Input() title       = 'Nothing here yet';
  @Input() description = '';
  @Input() actionLabel = '';
  @Output() onAction   = new EventEmitter<void>();
}

@Component({
    selector: 'tv-error-state',
    imports: [],
    template: `
    <div class="error-state">
      <div class="err-icon">⚠️</div>
      <div class="err-title">{{title || 'Something went wrong'}}</div>
      <div class="err-desc">{{message}}</div>
      @if (retryLabel) {
        <button class="err-btn" (click)="onRetry.emit()">{{retryLabel}}</button>
      }
    </div>
    `,
    styles: [`
    .error-state{display:flex;flex-direction:column;align-items:center;padding:32px 20px;text-align:center;gap:6px;background:rgba(255,59,92,0.04);border:1px solid rgba(255,59,92,0.15);border-radius:12px;}
    .err-icon{font-size:28px;}
    .err-title{font-size:14px;font-weight:700;color:#FF3B5C;}
    .err-desc{font-size:11px;color:#8895B3;max-width:260px;line-height:1.6;}
    .err-btn{margin-top:8px;padding:7px 18px;border-radius:8px;border:1px solid rgba(255,59,92,0.3);background:rgba(255,59,92,0.08);color:#FF3B5C;font-family:'Space Grotesk',sans-serif;font-size:11px;font-weight:700;cursor:pointer;transition:all 0.15s;&:hover{background:rgba(255,59,92,0.15);}}
  `]
})
export class ErrorStateComponent {
  @Input() title      = '';
  @Input() message    = 'Please check your connection and try again.';
  @Input() retryLabel = '↻ Retry';
  @Output() onRetry   = new EventEmitter<void>();
}
