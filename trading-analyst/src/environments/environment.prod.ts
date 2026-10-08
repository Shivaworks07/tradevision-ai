export const environment = {
  production:       true,
  apiUrl:           '/api',
  binanceUrl:       'https://api.binance.com',       // prod: call Binance directly from browser
  binanceFuturesUrl:'https://fapi.binance.com',
  forexUrl:         '/forex-api',     // still proxied via Spring Boot
  yahooUrl:         '/yf-api',        // still proxied via Spring Boot
  coingeckoUrl:     '/coingecko-api', // proxied through the backend rather than called directly from the browser, to satisfy CSP
  appName:          'TradeVision AI',
  version:          '1.0.0',
};
