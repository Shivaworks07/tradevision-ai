export const environment = {
  production:       false,
  apiUrl:           '/api',
  binanceUrl:       '/binance-spot',        // dev: use ng proxy
  binanceFuturesUrl:'/binance-futures',
  forexUrl:         '/forex-api',
  yahooUrl:         '/yf-api',
  coingeckoUrl:     '/coingecko-api',  // proxied through the backend, same pattern as forex/yahoo, to satisfy CSP
  appName:          'TradeVision AI',
  version:          '1.0.0',
};
