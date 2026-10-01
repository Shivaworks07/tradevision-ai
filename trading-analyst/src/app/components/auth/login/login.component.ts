import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../services/auth.service';

type Step = 'mobile' | 'otp';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  private auth   = inject(AuthService);
  private router = inject(Router);

  step:    Step   = 'mobile';
  mobile  = '';
  otp     = '';
  loading = false;
  error   = '';
  success = '';
  mobileError = '';
  otpError    = '';
  resendTimer = 0;
  private timerRef: any;

  validateMobile(): boolean {
    if (!this.mobile) { this.mobileError = 'Mobile number is required'; return false; }
    if (!/^[6-9]\d{9}$/.test(this.mobile)) { this.mobileError = 'Enter valid 10-digit Indian mobile number'; return false; }
    this.mobileError = ''; return true;
  }

  sendOtp() {
    if (!this.validateMobile()) return;
    this.loading = true; this.error = '';
    this.auth.initiateLogin(this.mobile).subscribe({
      next: r => {
        this.loading = false;
        if (r.success) { this.step = 'otp'; this.startResendTimer(); }
        else this.error = r.message;
      },
      error: e => { this.loading = false; this.error = e.error?.message || 'Server error. Is backend running?'; }
    });
  }

  verify() {
    if (!this.otp || this.otp.length !== 6) { this.otpError = 'Enter 6-digit OTP'; return; }
    this.otpError = ''; this.loading = true; this.error = '';
    this.auth.verifyLogin(this.mobile, this.otp).subscribe({
      next: r => {
        this.loading = false;
        if (r.success) { this.success = r.message; setTimeout(() => this.router.navigate(['/app']), 800); }
        else this.error = r.message;
      },
      error: e => { this.loading = false; this.error = e.status === 0 ? 'Service temporarily unavailable. Please try again later.' : (e.error?.message || 'Something went wrong. Please try again.'); }
    });
  }

  resend() {
    this.auth.resendOtp(this.mobile, 'LOGIN').subscribe({
      next: r => { if (r.success) { this.otp = ''; this.error = ''; this.startResendTimer(); } }
    });
  }

  startResendTimer() {
    this.resendTimer = 30; clearInterval(this.timerRef);
    this.timerRef = setInterval(() => { if (this.resendTimer > 0) this.resendTimer--; else clearInterval(this.timerRef); }, 1000);
  }

  backToMobile() { this.step = 'mobile'; this.otp = ''; this.error = ''; clearInterval(this.timerRef); }
  ngOnDestroy() { clearInterval(this.timerRef); }
}
