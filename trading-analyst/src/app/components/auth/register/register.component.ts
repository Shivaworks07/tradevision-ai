import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../services/auth.service';

type Step = 'form' | 'otp';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrls: ['./register.component.scss']
})
export class RegisterComponent {
  private auth   = inject(AuthService);
  private router = inject(Router);

  step:    Step    = 'form';
  loading          = false;
  error            = '';
  success          = '';
  resendTimer      = 0;
  private timerRef: any;

  form = { firstName:'', lastName:'', mobile:'', tradingPlatform:'' };
  otp  = '';

  platforms = ['Zerodha','Upstox','Angel One','Groww','5Paisa','ICICI Direct','HDFC Securities','Kotak Securities','Mudrex','WazirX','CoinDCX','Binance','Other'];

  errors: Record<string,string> = {};

  validate(): boolean {
    this.errors = {};
    if (!this.form.firstName.trim()) this.errors['firstName'] = 'First name is required';
    else if (this.form.firstName.length < 2) this.errors['firstName'] = 'Minimum 2 characters';
    if (!this.form.mobile) this.errors['mobile'] = 'Mobile number is required';
    else if (!/^[6-9]\d{9}$/.test(this.form.mobile)) this.errors['mobile'] = 'Enter valid 10-digit Indian mobile number';
    return Object.keys(this.errors).length === 0;
  }

  submit() {
    if (!this.validate()) return;
    this.loading = true; this.error = '';

    // Check duplicate mobile first
    this.auth.checkMobile(this.form.mobile).subscribe({
      next: r => {
        if (r.data?.registered) {
          this.loading = false;
          this.errors['mobile'] = 'This mobile number is already registered. Please login.';
          return;
        }
        // Proceed with registration
        this.auth.initiateRegister(this.form).subscribe({
          next: res => {
            this.loading = false;
            if (res.success) { this.step = 'otp'; this.startResendTimer(); }
            else this.error = res.message;
          },
          error: e => { this.loading = false; this.error = e.status === 0 ? 'Service temporarily unavailable. Please try again later.' : (e.error?.message || 'Something went wrong.'); }
        });
      },
      error: () => {
        // If check fails (backend down), try anyway
        this.auth.initiateRegister(this.form).subscribe({
          next: res => { this.loading=false; if(res.success){this.step='otp';this.startResendTimer();} else this.error=res.message; },
          error: e => { this.loading=false; this.error=e.error?.message||'Server error. Is backend running on port 8080?'; }
        });
      }
    });
  }

  verify() {
    if (!this.otp || this.otp.length !== 6) { this.error = 'Enter 6-digit OTP'; return; }
    this.loading = true; this.error = '';
    this.auth.verifyRegister(this.form.mobile, this.otp, this.form.firstName, this.form.lastName).subscribe({
      next: r => {
        this.loading = false;
        if (r.success) { this.success = r.message; setTimeout(() => this.router.navigate(['/app']), 1000); }
        else this.error = r.message;
      },
      error: e => { this.loading = false; this.error = e.status === 0 ? 'Service temporarily unavailable. Please try again later.' : (e.error?.message || 'Something went wrong. Please try again.'); }
    });
  }

  resend() {
    this.auth.resendOtp(this.form.mobile, 'REGISTER').subscribe({
      next: r => { if(r.success){ this.otp=''; this.error=''; this.startResendTimer(); } }
    });
  }

  startResendTimer() {
    this.resendTimer = 30; clearInterval(this.timerRef);
    this.timerRef = setInterval(() => { if(this.resendTimer>0) this.resendTimer--; else clearInterval(this.timerRef); }, 1000);
  }

  backToForm() { this.step='form'; this.otp=''; this.error=''; clearInterval(this.timerRef); }
  ngOnDestroy() { clearInterval(this.timerRef); }
}
