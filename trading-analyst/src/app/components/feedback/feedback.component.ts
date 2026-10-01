import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-feedback',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './feedback.component.html',
  styleUrls: ['./feedback.component.scss']
})
export class FeedbackComponent {
  private http = inject(HttpClient);
  auth         = inject(AuthService);
  private API  = `${environment.apiUrl}/feedback`;

  open     = false;
  sending  = false;
  sent     = false;
  error    = '';

  form = {
    type:        'BUG',
    title:       '',
    description: '',
    page:        window.location.pathname,
  };

  screenshot:     string | null = null;
  screenshotMime: string        = 'image/png';

  readonly types = [
    { value:'BUG',             label:'🐛 Bug Report'       },
    { value:'SUGGESTION',      label:'💡 Suggestion'        },
    { value:'FEATURE_REQUEST', label:'🚀 Feature Request'   },
    { value:'OTHER',           label:'💬 Other'             },
  ];

  toggle() {
    this.open = !this.open;
    if (!this.open) this.reset();
  }

  onFileChange(e: Event) {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { this.error = 'Screenshot must be under 5MB'; return; }
    this.screenshotMime = file.type;
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      this.screenshot = result.split(',')[1]; // strip data:image/png;base64,
    };
    reader.readAsDataURL(file);
  }

  removeScreenshot() { this.screenshot = null; }

  submit() {
    if (!this.form.title.trim() || !this.form.description.trim()) {
      this.error = 'Title and description are required'; return;
    }
    this.sending = true; this.error = '';

    const body = {
      ...this.form,
      screenshotBase64: this.screenshot,
      screenshotMime:   this.screenshot ? this.screenshotMime : null,
      userAgent:        navigator.userAgent,
      page:             window.location.pathname,
    };

    // Review finding ("Auth hardening" -- full context in AuthService's own javadoc): the token
    // itself is no longer readable here (HttpOnly cookie) -- withCredentials sends that cookie
    // automatically if the user happens to be logged in (this endpoint is public either way --
    // /api/feedback permits all in SecurityConfig -- so this only affects whether the backend
    // can optionally identify the submitter, never whether the submission itself succeeds).
    this.http.post<any>(this.API, body, { withCredentials: true }).subscribe({
      next:  () => { this.sending = false; this.sent = true; setTimeout(() => { this.sent = false; this.open = false; this.reset(); }, 2500); },
      error: e  => { this.sending = false; this.error = e?.error?.message || 'Failed to submit. Try again.'; }
    });
  }

  private reset() {
    this.form = { type:'BUG', title:'', description:'', page: window.location.pathname };
    this.screenshot = null; this.sent = false; this.error = '';
  }
}
