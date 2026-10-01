export const environment = {
  production:       false,
  apiUrl:           '/api',
  binanceUrl:       '/binance-spot',        // dev: use ng proxy
  binanceFuturesUrl:'/binance-futures',
  forexUrl:         '/forex-api',
  yahooUrl:         '/yf-api',
  coingeckoUrl:     '/coingecko-api',  // review finding (P1 — CSP + CoinGecko): now proxied, same pattern as forex/yahoo
  appName:          'TradeVision AI',
  version:          '1.0.0',
};
