import { Component, Input, Output, EventEmitter, inject, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { interval, Subscription } from 'rxjs';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-auth-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './auth-modal.component.html',
  styleUrls: ['./auth-modal.component.scss']
})
export class AuthModalComponent implements OnDestroy {
  @Input()  visible  = false;
  @Output() closed   = new EventEmitter<void>();
  @Output() loggedIn = new EventEmitter<void>();

  auth = inject(AuthService);
  http = inject(HttpClient);

  step      = 1;
  mode      = 'login';
  email     = '';
  firstName = '';
  lastName  = '';
  otpDigits: string[] = [];
  loading   = false;
  checking  = false;   // checking email existence
  error     = '';
  resendCooldown = 0;
  private cooldownSub?: Subscription;

  get otp() { return this.otpDigits.join(''); }

  // ── Validation ────────────────────────────────────────────
  private validateEmail(email: string): string | null {
    if (!email.trim()) return 'Email address is required.';
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
    if (!re.test(email.trim())) return 'Please enter a valid email address.';
    return null;
  }

  // ── Send OTP with all validations ────────────────────────
  sendOtp() {
    this.error = '';

    // Register validations
    if (this.mode === 'register') {
      if (!this.firstName.trim())
        { this.error = 'First name is required.'; return; }
      if (this.firstName.trim().length < 2)
        { this.error = 'First name must be at least 2 characters.'; return; }
    }

    const emailErr = this.validateEmail(this.email);
    if (emailErr) { this.error = emailErr; return; }

    const cleanEmail = this.email.trim().toLowerCase();
    this.checking = true;

    // Check email existence before sending OTP
    this.http.get<any>(`${environment.apiUrl}/auth/check-email?email=${encodeURIComponent(cleanEmail)}`)
      .subscribe({
        next: r => {
          this.checking = false;
          const registered = r?.data?.registered as boolean;

          if (this.mode === 'register' && registered) {
            this.error = 'An account with this email already exists. Please sign in instead.';
            return;
          }
          if (this.mode === 'login' && !registered) {
            this.error = 'No account found with this email. Please register first.';
            return;
          }

          // All good — send OTP
          this.doSendOtp(cleanEmail);
        },
        error: () => {
          this.checking = false;
          // If check fails (network), proceed anyway
          this.doSendOtp(cleanEmail);
        }
      });
  }

  private doSendOtp(cleanEmail: string) {
    this.loading = true; this.error = '';
    const obs = this.mode === 'register'
      ? this.auth.initiateRegister({
          email:     cleanEmail,
          firstName: this.firstName.trim(),
          lastName:  this.lastName.trim()
        })
      : this.auth.initiateLogin(cleanEmail);

    obs.subscribe({
      next:  () => { this.loading = false; this.step = 2; this.startCooldown(); },
      error:  e => { this.loading = false; this.error = e?.error?.message || 'Failed to send OTP. Try again.'; }
    });
  }

  // ── OTP box handlers ──────────────────────────────────────
  onOtpInput(e: Event, idx: number) {
    const val = (e.target as HTMLInputElement).value.replace(/\D/g,'').slice(-1);
    this.otpDigits = [...this.otpDigits];
    this.otpDigits[idx] = val;
    if (val && idx < 5) {
      setTimeout(() => (document.getElementById('otp'+(idx+1)) as HTMLInputElement)?.focus(), 0);
    }
    if (this.otpDigits.filter(Boolean).length === 6) this.verify();
  }

  onOtpKey(e: KeyboardEvent, idx: number) {
    if (e.key === 'Backspace' && !this.otpDigits[idx] && idx > 0) {
      this.otpDigits = [...this.otpDigits];
      this.otpDigits[idx-1] = '';
      setTimeout(() => (document.getElementById('otp'+(idx-1)) as HTMLInputElement)?.focus(), 0);
    }
  }

  onOtpPaste(e: ClipboardEvent) {
    e.preventDefault();
    const text = e.clipboardData?.getData('text')?.replace(/\D/g,'').slice(0,6) || '';
    this.otpDigits = text.split('');
    if (text.length === 6) setTimeout(() => this.verify(), 100);
    else setTimeout(() => (document.getElementById('otp'+text.length) as HTMLInputElement)?.focus(), 0);
  }

  // ── Verify ────────────────────────────────────────────────
  verify() {
    if (this.otp.length < 6) return;
    this.loading = true; this.error = '';
    const obs = this.mode === 'register'
      ? this.auth.verifyRegister(this.email.trim().toLowerCase(), this.otp)
      : this.auth.verifyLogin(this.email.trim().toLowerCase(), this.otp);
    obs.subscribe({
      next:  () => { this.loading = false; this.loggedIn.emit(); this.reset(); },
      error:  e => {
        this.loading = false;
        this.error = e?.error?.message || 'Invalid code. Please try again.';
        this.otpDigits = [];
        setTimeout(() => (document.getElementById('otp0') as HTMLInputElement)?.focus(), 100);
      }
    });
  }

  resend() {
    if (this.resendCooldown > 0) return;
    this.loading = true;
    this.auth.resendOtp(this.email, this.mode === 'register' ? 'REGISTER' : 'LOGIN').subscribe({
      next:  () => { this.loading = false; this.otpDigits = []; this.startCooldown(); },
      error: () => { this.loading = false; }
    });
  }

  private startCooldown() {
    this.resendCooldown = 30;
    this.cooldownSub?.unsubscribe();
    this.cooldownSub = interval(1000).subscribe(() => {
      if (--this.resendCooldown <= 0) this.cooldownSub?.unsubscribe();
    });
  }

  onClose() { this.closed.emit(); this.reset(); }

  private reset() {
    this.step = 1; this.otpDigits = []; this.error = ''; this.loading = false;
    this.checking = false; this.firstName = ''; this.lastName = '';
    this.resendCooldown = 0; this.cooldownSub?.unsubscribe();
  }

  ngOnDestroy() { this.cooldownSub?.unsubscribe(); }
}
