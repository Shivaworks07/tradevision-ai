// Custom Karma config, added purely to make `ng test` runnable in this specific sandboxed CI-
// like environment (Chrome refuses to launch as root without --no-sandbox: "Running as root
// without --no-sandbox is not supported"). ChromeHeadlessNoSandbox is Karma's own documented
// pattern for exactly this situation (a custom launcher extending the built-in ChromeHeadless
// with extra flags), not a modification of Angular's own default test behavior for a normal,
// non-root developer machine or a properly-configured CI runner.
//
// CHROME_BIN must be set in the environment running this (this sandbox has no `chrome`/
// `chromium` on PATH, only a cached Puppeteer download at a machine-specific path) -- this
// deliberately does NOT hardcode that path or assume a `puppeteer` npm package (checked: not a
// dependency of this project) is installed to auto-discover it.

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: {},
      clearContext: false,
    },
    jasmineHtmlReporter: {
      suppressAll: true,
    },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/trading-analyst'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }],
    },
    reporters: ['progress', 'kjhtml'],
    port: 9876,
    colors: true,
    logLevel: config.LOG_INFO,
    autoWatch: true,
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
      },
    },
    browsers: ['ChromeHeadlessNoSandbox'],
    singleRun: false,
    restartOnFileChange: true,
  });
};
