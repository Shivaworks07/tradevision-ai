// Fades out and hides the #app-loader splash screen shortly after the page loads. Kept as an
// external file (served from this same origin, under /assets) rather than inline in index.html
// because the backend's CSP intentionally has no 'unsafe-inline' on script-src (see
// SecurityConfig's own comment) -- an inline <script> here would be silently blocked by the
// browser, leaving the full-screen loader permanently covering the app underneath it.
document.addEventListener('DOMContentLoaded', function() {
  setTimeout(function() {
    var l = document.getElementById('app-loader');
    if (l) { l.classList.add('fade-out'); setTimeout(function(){l.style.display='none';},600); }
  }, 2000);
});
