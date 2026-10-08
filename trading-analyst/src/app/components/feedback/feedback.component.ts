import { Component, inject, signal } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { environment } from '../../../environments/environment';

@Component({
    selector: 'app-feedback',
    imports: [FormsModule],
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

    // withCredentials sends the HttpOnly auth cookie when the user is logged in, so the
    // backend can optionally identify the submitter. The endpoint itself is public, so a
    // logged-out submission still succeeds the same way.
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
