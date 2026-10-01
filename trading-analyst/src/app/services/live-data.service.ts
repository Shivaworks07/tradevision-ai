import { environment } from '../../environments/environment';
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, interval, of, forkJoin, timer } from 'rxjs';
import { retryWhen, delayWhen, take, tap } from 'rxjs/operators';
import { switchMap, startWith, map, catchError } from 'rxjs/operators';

export interface LiveQuote {
  symbol: string; name: string; price: number;
  change: number; changePct: number;
  high: number; low: number; open: number;
  volume: number; marketCap?: number; sector?: string; exchange?: string;
  // Review finding (P1 #8/#10 — "The frontend still fabricates market data" / "Index fallback
  // is also fake market data"): confirmed real — fallback numbers were shown with no indication
  // they weren't live. Matches the existing IPOResponse.stale precedent in this same file.
  stale?: boolean;
  // Review finding ("BUT — there is still fake/derived FX market data"): confirmed real — even
  // with a genuinely live ECB rate, change/high/low/open were still Math.random()-derived. This
  // flag lets the FX template show "—" for those specific fields rather than a fabricated
  // number, while the price itself stays real. See buildFxQuotes' own comment for the full fix.
  ohlcUnavailable?: boolean;
}
export interface IPOItem {
  company: string; symbol: string; openDate: string|null; closeDate: string|null; listingDate: string|null;
  priceMin: number; priceMax: number; lotSize: number; gmp: number|null; gmpPct: number|null;
  status: 'OPEN'|'UPCOMING'|'CLOSED'|'LISTED'|'UNKNOWN';
  category: string; issueSize: string; exchange: string; source: string;
}

export interface IPOResponse {
  ipos: IPOItem[]; total: number; lastUpdated: number; stale: boolean;
  warning?: string; gmpDisclaimer: string;
}

@Injectable({ providedIn: 'root' })
export class LiveDataService {
  // Retry with exponential backoff: 1s, 2s, 4s then give up
  private retryBackoff<T>(maxRetries = 3) {
    return retryWhen<T>(errors =>
      errors.pipe(
        delayWhen((_, i) => timer(Math.pow(2, i) * 1000)),
        take(maxRetries),
        tap(()=>{}, ()=>{})
      )
    );
  }

  // ── 200+ NSE stocks with sector tags ──────────────────────
  readonly ALL_NSE_STOCKS = [
    // Large Cap / Nifty 50
    {sym:'RELIANCE.NS',   name:'Reliance Industries',    sector:'Energy'},
    {sym:'TCS.NS',        name:'TCS',                    sector:'IT'},
    {sym:'HDFCBANK.NS',   name:'HDFC Bank',              sector:'Banking'},
    {sym:'INFY.NS',       name:'Infosys',                sector:'IT'},
    {sym:'ICICIBANK.NS',  name:'ICICI Bank',             sector:'Banking'},
    {sym:'HINDUNILVR.NS', name:'Hindustan Unilever',     sector:'FMCG'},
    {sym:'SBIN.NS',       name:'State Bank of India',    sector:'Banking'},
    {sym:'BHARTIARTL.NS', name:'Bharti Airtel',          sector:'Telecom'},
    {sym:'ITC.NS',        name:'ITC Ltd',                sector:'FMCG'},
    {sym:'KOTAKBANK.NS',  name:'Kotak Mahindra Bank',    sector:'Banking'},
    {sym:'LT.NS',         name:'Larsen & Toubro',        sector:'Infrastructure'},
    {sym:'AXISBANK.NS',   name:'Axis Bank',              sector:'Banking'},
    {sym:'ASIANPAINT.NS', name:'Asian Paints',           sector:'Chemicals'},
    {sym:'MARUTI.NS',     name:'Maruti Suzuki',          sector:'Auto'},
    {sym:'SUNPHARMA.NS',  name:'Sun Pharma',             sector:'Pharma'},
    {sym:'TATAMOTORS.NS', name:'Tata Motors (CV & PV)',            sector:'Auto'},
    {sym:'WIPRO.NS',      name:'Wipro',                  sector:'IT'},
    {sym:'ULTRACEMCO.NS', name:'UltraTech Cement',       sector:'Cement'},
    {sym:'BAJFINANCE.NS', name:'Bajaj Finance',          sector:'Finance'},
    {sym:'ONGC.NS',       name:'ONGC',                   sector:'Energy'},
    {sym:'NTPC.NS',       name:'NTPC',                   sector:'Power'},
    {sym:'ADANIENT.NS',   name:'Adani Enterprises',      sector:'Conglomerate'},
    {sym:'POWERGRID.NS',  name:'Power Grid Corp',        sector:'Power'},
    {sym:'M&M.NS',        name:'Mahindra & Mahindra',    sector:'Auto'},
    {sym:'NESTLEIND.NS',  name:'Nestle India',           sector:'FMCG'},
    {sym:'TECHM.NS',      name:'Tech Mahindra',          sector:'IT'},
    {sym:'HCLTECH.NS',    name:'HCL Technologies',       sector:'IT'},
    {sym:'BAJAJFINSV.NS', name:'Bajaj Finserv',          sector:'Finance'},
    {sym:'TITAN.NS',      name:'Titan Company',          sector:'Consumer'},
    {sym:'TATASTEEL.NS',  name:'Tata Steel',             sector:'Metals'},
    // Mid Cap
    {sym:'ADANIPORTS.NS', name:'Adani Ports',            sector:'Infrastructure'},
    {sym:'ADANIGREEN.NS', name:'Adani Green Energy',     sector:'Renewable'},
    {sym:'ADANIPOWER.NS', name:'Adani Power',            sector:'Power'},
    {sym:'SIEMENS.NS',    name:'Siemens India',          sector:'Engineering'},
    {sym:'HAVELLS.NS',    name:'Havells India',          sector:'Electricals'},
    {sym:'PIDILITIND.NS', name:'Pidilite Industries',    sector:'Chemicals'},
    {sym:'DMART.NS',      name:'DMart (Avenue Supermarts)',sector:'Retail'},
    {sym:'NAUKRI.NS',     name:'Info Edge (Naukri)',     sector:'IT'},
    {sym:'ZOMATO.NS',     name:'Zomato',                 sector:'Consumer Tech'},
    {sym:'PAYTM.NS',      name:'Paytm (One97 Comm)',     sector:'Fintech'},
    {sym:'NYKAA.NS',      name:'Nykaa (FSN E-Commerce)', sector:'Consumer Tech'},
    {sym:'POLICYBZR.NS',  name:'PB Fintech (Policybazaar)',sector:'Fintech'},
    {sym:'IRCTC.NS',      name:'IRCTC',                  sector:'Travel'},
    {sym:'INDIGO.NS',     name:'IndiGo (InterGlobe)',    sector:'Aviation'},
    {sym:'VEDL.NS',       name:'Vedanta',                sector:'Metals'},
    {sym:'HINDALCO.NS',   name:'Hindalco Industries',    sector:'Metals'},
    {sym:'JSWSTEEL.NS',   name:'JSW Steel',              sector:'Metals'},
    {sym:'COALINDIA.NS',  name:'Coal India',             sector:'Mining'},
    {sym:'GRASIM.NS',     name:'Grasim Industries',      sector:'Cement'},
    {sym:'SHREECEM.NS',   name:'Shree Cement',           sector:'Cement'},
    {sym:'AMBUJACEM.NS',  name:'Ambuja Cements',         sector:'Cement'},
    {sym:'DRREDDY.NS',    name:'Dr. Reddy\'s Labs',      sector:'Pharma'},
    {sym:'CIPLA.NS',      name:'Cipla',                  sector:'Pharma'},
    {sym:'DIVISLAB.NS',   name:'Divi\'s Laboratories',   sector:'Pharma'},
    {sym:'APOLLOHOSP.NS', name:'Apollo Hospitals',       sector:'Healthcare'},
    {sym:'FORTIS.NS',     name:'Fortis Healthcare',      sector:'Healthcare'},
    {sym:'MAXHEALTH.NS',  name:'Max Healthcare',         sector:'Healthcare'},
    {sym:'BAJAJ-AUTO.NS', name:'Bajaj Auto',             sector:'Auto'},
    {sym:'EICHERMOT.NS',  name:'Eicher Motors',          sector:'Auto'},
    {sym:'HEROMOTOCO.NS', name:'Hero MotoCorp',          sector:'Auto'},
    {sym:'TATAPOWER.NS',  name:'Tata Power',             sector:'Power'},
    {sym:'RECLTD.NS',     name:'REC Ltd',                sector:'Finance'},
    {sym:'PFC.NS',        name:'Power Finance Corp',     sector:'Finance'},
    {sym:'BANKBARODA.NS', name:'Bank of Baroda',         sector:'Banking'},
    {sym:'CANBK.NS',      name:'Canara Bank',            sector:'Banking'},
    {sym:'FEDERALBNK.NS', name:'Federal Bank',           sector:'Banking'},
    {sym:'INDUSINDBK.NS', name:'IndusInd Bank',          sector:'Banking'},
    {sym:'BANDHANBNK.NS', name:'Bandhan Bank',           sector:'Banking'},
    {sym:'IDFCFIRSTB.NS', name:'IDFC First Bank',        sector:'Banking'},
    {sym:'YESBANK.NS',    name:'Yes Bank',               sector:'Banking'},
    {sym:'IDEA.NS',       name:'Vodafone Idea',          sector:'Telecom'},
    {sym:'MTNL.NS',       name:'MTNL',                   sector:'Telecom'},
    {sym:'HAL.NS',        name:'Hindustan Aeronautics',  sector:'Defence'},
    {sym:'BEL.NS',        name:'Bharat Electronics',     sector:'Defence'},
    {sym:'BHEL.NS',       name:'BHEL',                   sector:'Engineering'},
    {sym:'IRFC.NS',       name:'IRFC',                   sector:'Finance'},
    {sym:'RVNL.NS',       name:'Rail Vikas Nigam',       sector:'Infrastructure'},
    {sym:'IRCON.NS',      name:'Ircon International',    sector:'Infrastructure'},
    {sym:'NHPC.NS',       name:'NHPC',                   sector:'Power'},
    {sym:'SJVN.NS',       name:'SJVN',                   sector:'Power'},
    {sym:'CESC.NS',       name:'CESC',                   sector:'Power'},
    {sym:'TORNTPOWER.NS', name:'Torrent Power',          sector:'Power'},
    {sym:'ZYDUSLIFE.NS',  name:'Zydus Lifesciences',     sector:'Pharma'},
    {sym:'LUPIN.NS',      name:'Lupin',                  sector:'Pharma'},
    {sym:'AUROPHARMA.NS', name:'Aurobindo Pharma',       sector:'Pharma'},
    {sym:'ALKEM.NS',      name:'Alkem Laboratories',     sector:'Pharma'},
    {sym:'ABBOTT.NS',     name:'Abbott India',           sector:'Pharma'},
    {sym:'PFIZER.NS',     name:'Pfizer India',           sector:'Pharma'},
    {sym:'SANOFI.NS',     name:'Sanofi India',           sector:'Pharma'},
    {sym:'VOLTAS.NS',     name:'Voltas',                 sector:'Consumer'},
    {sym:'WHIRLPOOL.NS',  name:'Whirlpool India',        sector:'Consumer'},
    {sym:'BLUESTARCO.NS', name:'Blue Star',              sector:'Consumer'},
    {sym:'KALYANKJIL.NS', name:'Kalyan Jewellers',       sector:'Jewellery'},
    {sym:'SENCO.NS',      name:'Senco Gold',             sector:'Jewellery'},
    {sym:'MUTHOOTFIN.NS', name:'Muthoot Finance',        sector:'Finance'},
    {sym:'CHOLAFIN.NS',   name:'Cholamandalam Finance',  sector:'Finance'},
    {sym:'MANAPPURAM.NS', name:'Manappuram Finance',     sector:'Finance'},
    {sym:'MOTHERSON.NS',  name:'Samvardhana Motherson',  sector:'Auto Ancil'},
    {sym:'BOSCHLTD.NS',   name:'Bosch India',            sector:'Auto Ancil'},
    {sym:'MRF.NS',        name:'MRF',                    sector:'Auto Ancil'},
    {sym:'APOLLOTYRE.NS', name:'Apollo Tyres',           sector:'Auto Ancil'},
    {sym:'CEATLTD.NS',    name:'CEAT',                   sector:'Auto Ancil'},
    {sym:'PERSISTENT.NS', name:'Persistent Systems',     sector:'IT'},
    {sym:'MPHASIS.NS',    name:'Mphasis',                sector:'IT'},
    {sym:'LTIM.NS',       name:'LTIMindtree',            sector:'IT'},
    {sym:'COFORGE.NS',    name:'Coforge',                sector:'IT'},
    {sym:'KPITTECH.NS',   name:'KPIT Technologies',      sector:'IT'},
    {sym:'TATAELXSI.NS',  name:'Tata Elxsi',             sector:'IT'},
    {sym:'INFY.NS',       name:'Infosys',                sector:'IT'},
    {sym:'OFSS.NS',       name:'Oracle Financial Services',sector:'IT'},
    {sym:'FSL.NS',        name:'Firstsource Solutions',  sector:'IT'},
    {sym:'REDINGTON.NS',  name:'Redington India',        sector:'IT'},
    {sym:'DELHIVERY.NS',  name:'Delhivery',              sector:'Logistics'},
    {sym:'BLUEDART.NS',   name:'Blue Dart Express',      sector:'Logistics'},
    {sym:'CONCOR.NS',     name:'Container Corp',         sector:'Logistics'},
    {sym:'INTERGLOBE.NS', name:'InterGlobe Aviation',    sector:'Aviation'},
    {sym:'SPICEJET.NS',   name:'SpiceJet',               sector:'Aviation'},
    {sym:'GMRINFRA.NS',   name:'GMR Airports Infra',     sector:'Infrastructure'},
    {sym:'AIAENG.NS',     name:'AIA Engineering',        sector:'Engineering'},
    {sym:'ABB.NS',        name:'ABB India',              sector:'Engineering'},
    {sym:'CUMMINSIND.NS', name:'Cummins India',          sector:'Engineering'},
    {sym:'THERMAX.NS',    name:'Thermax',                sector:'Engineering'},
    {sym:'PAGEIND.NS',    name:'Page Industries (Jockey)',sector:'Textiles'},
    {sym:'RAYMOND.NS',    name:'Raymond',                sector:'Textiles'},
    {sym:'TRIDENT.NS',    name:'Trident',                sector:'Textiles'},
    {sym:'GLAND.NS',      name:'Gland Pharma',           sector:'Pharma'},
    {sym:'METROPOLIS.NS', name:'Metropolis Healthcare',  sector:'Healthcare'},
    {sym:'LALPATHLAB.NS', name:'Dr. Lal PathLabs',       sector:'Healthcare'},
    {sym:'SBILIFE.NS',    name:'SBI Life Insurance',     sector:'Insurance'},
    {sym:'HDFCLIFE.NS',   name:'HDFC Life Insurance',    sector:'Insurance'},
    {sym:'ICICIGI.NS',    name:'ICICI General Insurance', sector:'Insurance'},
    {sym:'BAJAJHLDNG.NS', name:'Bajaj Holdings',         sector:'Finance'},
    {sym:'NAUKRI.NS',     name:'Naukri (Info Edge)',      sector:'Consumer Tech'},
    {sym:'JUSTDIAL.NS',   name:'Just Dial',              sector:'Consumer Tech'},
    {sym:'MAPMYINDIA.NS', name:'MapMyIndia (CE Info)',   sector:'Consumer Tech'},
  ];

  // ── All crypto coins searchable ───────────────────────────
  readonly ALL_CRYPTO = [
    {id:'bitcoin',          symbol:'BTC',    name:'Bitcoin'},
    {id:'ethereum',         symbol:'ETH',    name:'Ethereum'},
    {id:'solana',           symbol:'SOL',    name:'Solana'},
    {id:'binancecoin',      symbol:'BNB',    name:'BNB'},
    {id:'ripple',           symbol:'XRP',    name:'XRP'},
    {id:'cardano',          symbol:'ADA',    name:'Cardano'},
    {id:'avalanche-2',      symbol:'AVAX',   name:'Avalanche'},
    {id:'dogecoin',         symbol:'DOGE',   name:'Dogecoin'},
    {id:'polkadot',         symbol:'DOT',    name:'Polkadot'},
    {id:'chainlink',        symbol:'LINK',   name:'Chainlink'},
    {id:'ondo-finance',     symbol:'ONDO',   name:'Ondo Finance'},
    {id:'fetch-ai',         symbol:'FET',    name:'Fetch.ai'},
    {id:'near',             symbol:'NEAR',   name:'NEAR Protocol'},
    {id:'uniswap',          symbol:'UNI',    name:'Uniswap'},
    {id:'injective-protocol',symbol:'INJ',  name:'Injective'},
    {id:'shiba-inu',        symbol:'SHIB',   name:'Shiba Inu'},
    {id:'litecoin',         symbol:'LTC',    name:'Litecoin'},
    {id:'polygon',          symbol:'MATIC',  name:'Polygon'},
    {id:'tron',             symbol:'TRX',    name:'TRON'},
    {id:'stellar',          symbol:'XLM',    name:'Stellar'},
    {id:'aptos',            symbol:'APT',    name:'Aptos'},
    {id:'sui',              symbol:'SUI',    name:'Sui'},
    {id:'arbitrum',         symbol:'ARB',    name:'Arbitrum'},
    {id:'optimism',         symbol:'OP',     name:'Optimism'},
    {id:'the-graph',        symbol:'GRT',    name:'The Graph'},
    {id:'filecoin',         symbol:'FIL',    name:'Filecoin'},
    {id:'cosmos',           symbol:'ATOM',   name:'Cosmos'},
    {id:'algorand',         symbol:'ALGO',   name:'Algorand'},
    {id:'vechain',          symbol:'VET',    name:'VeChain'},
    {id:'internet-computer',symbol:'ICP',   name:'Internet Computer'},
    {id:'hedera-hashgraph', symbol:'HBAR',   name:'Hedera'},
    {id:'render-token',     symbol:'RNDR',   name:'Render'},
    {id:'immutable-x',      symbol:'IMX',    name:'Immutable X'},
    {id:'mantra-dao',       symbol:'OM',     name:'MANTRA'},
    {id:'worldcoin-wld',    symbol:'WLD',    name:'Worldcoin'},
    {id:'sei-network',      symbol:'SEI',    name:'Sei'},
    {id:'celestia',         symbol:'TIA',    name:'Celestia'},
    {id:'starknet',         symbol:'STRK',   name:'Starknet'},
    {id:'pepe',             symbol:'PEPE',   name:'Pepe'},
    {id:'bonk',             symbol:'BONK',   name:'Bonk'},
    {id:'wormhole',         symbol:'W',      name:'Wormhole'},
    {id:'jupiter-exchange-solana',symbol:'JUP',name:'Jupiter'},
    {id:'jito-governance-token',symbol:'JTO',name:'Jito'},
    {id:'pyth-network',     symbol:'PYTH',   name:'Pyth Network'},
    {id:'tensor',           symbol:'TNSR',   name:'Tensor'},
    {id:'sonic-svm',        symbol:'S',      name:'Sonic'},
    {id:'axelar',           symbol:'AXL',    name:'Axelar'},
    {id:'theta-token',      symbol:'THETA',  name:'Theta Network'},
    {id:'aave',             symbol:'AAVE',   name:'Aave'},
    {id:'maker',            symbol:'MKR',    name:'Maker'},
  ];

  // ── All forex pairs ───────────────────────────────────────
  readonly ALL_FOREX_PAIRS = [
    // INR pairs
    {sym:'USD/INR', name:'US Dollar / Indian Rupee',      base:'USD', quote:'INR'},
    {sym:'EUR/INR', name:'Euro / Indian Rupee',           base:'EUR', quote:'INR'},
    {sym:'GBP/INR', name:'British Pound / Indian Rupee',  base:'GBP', quote:'INR'},
    {sym:'JPY/INR', name:'Japanese Yen / Indian Rupee',   base:'JPY', quote:'INR'},
    {sym:'AUD/INR', name:'Australian Dollar / Indian Rupee',base:'AUD',quote:'INR'},
    {sym:'CAD/INR', name:'Canadian Dollar / Indian Rupee',base:'CAD', quote:'INR'},
    {sym:'CHF/INR', name:'Swiss Franc / Indian Rupee',    base:'CHF', quote:'INR'},
    {sym:'SGD/INR', name:'Singapore Dollar / Indian Rupee',base:'SGD',quote:'INR'},
    {sym:'HKD/INR', name:'Hong Kong Dollar / Indian Rupee',base:'HKD',quote:'INR'},
    {sym:'AED/INR', name:'UAE Dirham / Indian Rupee',     base:'AED', quote:'INR'},
    {sym:'SAR/INR', name:'Saudi Riyal / Indian Rupee',    base:'SAR', quote:'INR'},
    {sym:'MYR/INR', name:'Malaysian Ringgit / Indian Rupee',base:'MYR',quote:'INR'},
    // Major pairs
    {sym:'EUR/USD', name:'Euro / US Dollar',              base:'EUR', quote:'USD'},
    {sym:'GBP/USD', name:'British Pound / US Dollar',     base:'GBP', quote:'USD'},
    {sym:'USD/JPY', name:'US Dollar / Japanese Yen',      base:'USD', quote:'JPY'},
    {sym:'USD/CHF', name:'US Dollar / Swiss Franc',       base:'USD', quote:'CHF'},
    {sym:'USD/CAD', name:'US Dollar / Canadian Dollar',   base:'USD', quote:'CAD'},
    {sym:'AUD/USD', name:'Australian Dollar / US Dollar', base:'AUD', quote:'USD'},
    {sym:'NZD/USD', name:'New Zealand Dollar / USD',      base:'NZD', quote:'USD'},
    {sym:'USD/SGD', name:'US Dollar / Singapore Dollar',  base:'USD', quote:'SGD'},
    {sym:'USD/HKD', name:'US Dollar / Hong Kong Dollar',  base:'USD', quote:'HKD'},
    {sym:'USD/MXN', name:'US Dollar / Mexican Peso',      base:'USD', quote:'MXN'},
    {sym:'USD/ZAR', name:'US Dollar / South African Rand',base:'USD', quote:'ZAR'},
    {sym:'USD/NOK', name:'US Dollar / Norwegian Krone',   base:'USD', quote:'NOK'},
    {sym:'USD/SEK', name:'US Dollar / Swedish Krona',     base:'USD', quote:'SEK'},
    {sym:'USD/DKK', name:'US Dollar / Danish Krone',      base:'USD', quote:'DKK'},
    // Cross pairs
    {sym:'EUR/GBP', name:'Euro / British Pound',          base:'EUR', quote:'GBP'},
    {sym:'EUR/JPY', name:'Euro / Japanese Yen',           base:'EUR', quote:'JPY'},
    {sym:'GBP/JPY', name:'British Pound / Japanese Yen',  base:'GBP', quote:'JPY'},
    {sym:'EUR/CHF', name:'Euro / Swiss Franc',            base:'EUR', quote:'CHF'},
    {sym:'AUD/JPY', name:'Australian Dollar / Yen',       base:'AUD', quote:'JPY'},
  ];

  constructor(private http: HttpClient) {}

  // ── Yahoo Finance via proxy (/yf-api) ─────────────────────
  private yfChart(symbol: string, params: string): Observable<any> {
    return this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/${symbol}?${params}`)
      .pipe(catchError(() => of(null)));
  }

  // ── Search NSE stocks by query ────────────────────────────
  // Local fallback for instant results / offline safety — covers only the
  // curated large-cap list. The real, full ~2000-symbol NSE universe comes
  // from searchNSEStocksLive() below.
  searchNSEStocksLocal(query: string): { sym: string; name: string; sector: string }[] {
    if (!query || query.length < 1) return this.ALL_NSE_STOCKS.slice(0, 30);
    const q = query.toLowerCase();
    return this.ALL_NSE_STOCKS.filter(s =>
      s.sym.toLowerCase().includes(q) ||
      s.name.toLowerCase().includes(q) ||
      s.sector.toLowerCase().includes(q)
    ).slice(0, 50);
  }

  // Live search across the full NSE equity universe (~2000 symbols), backed
  // by the server-side symbol cache. Falls back to the local curated list on
  // any network failure so search never goes empty.
  searchNSEStocksLive(query: string): Observable<{ sym: string; name: string; sector: string }[]> {
    return this.http.get<any>(`${environment.apiUrl}/stocks/symbols/search?q=${encodeURIComponent(query)}&limit=50`).pipe(
      map(r => {
        const results = r?.data?.results as { symbol: string; name: string }[] | undefined;
        if (!results || results.length === 0) return this.searchNSEStocksLocal(query);
        return results.map(x => ({ sym: x.symbol + '.NS', name: x.name, sector: '' }));
      }),
      catchError(() => of(this.searchNSEStocksLocal(query)))
    );
  }

  // ── Fetch single stock quote ──────────────────────────────
  fetchStockQuote(s: { sym: string; name: string; sector: string }): Observable<LiveQuote | null> {
    return this.yfChart(s.sym, 'range=5d&interval=1d&includePrePost=false&events=div%7Csplit&lang=en-US&region=US').pipe(
      map(data => {
        const meta = data?.chart?.result?.[0]?.meta;
        if (!meta?.regularMarketPrice) return null;
        const prev = meta.chartPreviousClose || meta.previousClose || meta.regularMarketPrice;
        const price = meta.regularMarketPrice;
        const chg = price - prev;
        const pct = prev > 0 ? (chg / prev) * 100 : 0;
        return {
          symbol: s.sym.replace('.NS',''), name: s.name, sector: s.sector, exchange: 'NSE',
          price, change: +chg.toFixed(2), changePct: +pct.toFixed(2),
          high:   meta.regularMarketDayHigh  || price * 1.01,
          low:    meta.regularMarketDayLow   || price * 0.99,
          open:   meta.regularMarketOpen     || price,
          volume: meta.regularMarketVolume   || 0,
        } as LiveQuote;
      })
    );
  }

  // ── Default 20 stocks (Nifty top) auto-loaded ────────────
  getIndianStocks(): Observable<LiveQuote[]> {
    const top20 = this.ALL_NSE_STOCKS.slice(0, 20);
    return interval(60000).pipe(
      startWith(0),
      switchMap(() => forkJoin(top20.map(s => this.fetchStockQuote(s)))),
      map(results => {
        const live = results.filter(r => r !== null) as LiveQuote[];
        return live.length >= 8 ? live.filter(Boolean) as LiveQuote[] : this.indianFallback();
      })
    );
  }

  // ── 3-month daily candles for TA ─────────────────────────
  getIndianKlines(nseSymbol: string): Observable<any[]> {
    return this.yfChart(nseSymbol, 'range=3mo&interval=1d&events=div&lang=en-US&region=US').pipe(
      map(data => {
        if (!data?.chart?.result?.[0]) return [];
        const r = data.chart.result[0];
        const ts = r.timestamp || [];
        const q = r.indicators?.quote?.[0] || {};
        return ts.map((t: number, i: number) => ({
          time: t, open: q.open?.[i]||0, high: q.high?.[i]||0,
          low: q.low?.[i]||0, close: q.close?.[i]||0, volume: q.volume?.[i]||0,
        })).filter((c: any) => c.close > 0);
      })
    );
  }

  // ── Indian Stock Klines with range + interval param ──────
  // interval defaults to '1d' to keep every existing daily-chart call site
  // working unchanged; intraday chart use passes '5m'/'15m'/etc explicitly.
  getIndianKlinesRange(nseSymbol: string, range: string, interval: string = '1d'): Observable<any[]> {
    return this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/${nseSymbol}?range=${range}&interval=${interval}&events=div&lang=en-US&region=US`).pipe(
      catchError(() => of(null)),
      map(data => {
        if (!data?.chart?.result?.[0]) return [];
        const r = data.chart.result[0];
        const ts = r.timestamp || [];
        const q = r.indicators?.quote?.[0] || {};
        return ts.map((t: number, i: number) => ({
          time: t, open: q.open?.[i]||0, high: q.high?.[i]||0,
          low: q.low?.[i]||0, close: q.close?.[i]||0, volume: q.volume?.[i]||0,
        })).filter((c: any) => c.close > 0);
      })
    );
  }

  // ── Indian Klines once (no interval polling - for MTF) ──────
  getIndianKlinesOnce(nseSymbol: string, range: string, interval: string): Observable<any[]> {
    return this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/${nseSymbol}?range=${range}&interval=${interval}&events=div&lang=en-US`).pipe(
      catchError(() => of(null)),
      map((data: any) => {
        if (!data?.chart?.result?.[0]) return [];
        const r = data.chart.result[0];
        const ts = r.timestamp || [];
        const q = r.indicators?.quote?.[0] || {};
        return ts.map((t: number, i: number) => ({
          time: t, open: q.open?.[i]||0, high: q.high?.[i]||0,
          low: q.low?.[i]||0, close: q.close?.[i]||0, volume: q.volume?.[i]||0,
        })).filter((can: any) => can.close > 0);
      })
    );
  }

  // ── Indices ───────────────────────────────────────────────
  getIndexQuotes(): Observable<{ nifty: any; sensex: any }> {
    return forkJoin({
      n: this.yfChart('%5ENSEI',  'range=1d&interval=1d&lang=en-US'),
      b: this.yfChart('%5EBSESN', 'range=1d&interval=1d&lang=en-US'),
    }).pipe(
      map(({ n, b }) => {
        const fmt = (data: any, fbVal: string) => {
          const m = data?.chart?.result?.[0]?.meta;
          if (m?.regularMarketPrice) {
            const prev = m.chartPreviousClose || m.regularMarketPrice;
            const chg = m.regularMarketPrice - prev;
            const pct = prev > 0 ? (chg/prev)*100 : 0;
            return { value: m.regularMarketPrice.toLocaleString('en-IN',{maximumFractionDigits:2}), change:(chg>=0?'+':'')+chg.toFixed(2), pct:(pct>=0?'+':'')+pct.toFixed(2), up:chg>=0, stale: false };
          }
          // Review finding (P1 #10 — "Index fallback is also fake market data"): confirmed
          // real — these static values had no available/stale state attached at all.
          return { value: fbVal, change: '0.00', pct: '0.00', up: true, stale: true };
        };
        return { nifty: fmt(n,'24,013'), sensex: fmt(b,'79,212') };
      })
    );
  }

  // ── CRYPTO: CoinGecko with search support ─────────────────
  getCryptoByIds(ids: string, currency: 'usd'|'inr' = 'usd'): Observable<LiveQuote[]> {
    return this.http.get<any[]>(`${environment.coingeckoUrl}/api/v3/coins/markets`, {
      params: { vs_currency: currency, ids, order: 'market_cap_desc', per_page: '50', page: '1', sparkline: 'false', price_change_percentage: '24h' }
    }).pipe(
      catchError(() => of([])),
      map(data => data.map(c => ({
        symbol: c.symbol?.toUpperCase(), name: c.name,
        price: c.current_price||0, change: c.price_change_24h||0,
        changePct: c.price_change_percentage_24h||0,
        high: c.high_24h||0, low: c.low_24h||0,
        open: (c.current_price||0)-(c.price_change_24h||0),
        volume: c.total_volume||0, marketCap: c.market_cap||0,
      })))
    );
  }

  getCryptoLive(currency: 'usd'|'inr' = 'usd'): Observable<LiveQuote[]> {
    const defaultIds = 'bitcoin,ethereum,solana,ondo-finance,fetch-ai,binancecoin,ripple,cardano,avalanche-2,chainlink,polkadot,dogecoin,near,uniswap,injective-protocol,shiba-inu,litecoin,polygon,tron,aptos';
    return interval(30000).pipe(
      startWith(0),
      switchMap(() => this.getCryptoByIds(defaultIds, currency))
    );
  }

  searchCryptoCoins(query: string): { id: string; symbol: string; name: string }[] {
    if (!query) return this.ALL_CRYPTO.slice(0, 20);
    const q = query.toLowerCase();
    return this.ALL_CRYPTO.filter(c =>
      c.symbol.toLowerCase().includes(q) || c.name.toLowerCase().includes(q)
    );
  }

  // ── CRYPTO OHLCV: Binance ─────────────────────────────────
  // Map display timeframes to Binance API intervals
  readonly TF_MAP: Record<string,string> = {
    '1m':'1m','3m':'3m','5m':'5m','6m':'6h', // 6m display = 6h API
    '15m':'15m','30m':'30m','1h':'1h','4h':'4h',
    '1d':'1d','1w':'1w','1M':'1M',
    '1y':'1d','3y':'1w' // 1y/3y use daily/weekly with large limit
  };

  // Limit based on timeframe
  private tfLimit(tf: string): number {
    const limits: Record<string,number> = {
      '1m':500,'3m':500,'5m':500,'6m':500,'15m':500,'30m':500,
      '1h':500,'4h':500,'1d':365,'1w':156,'1M':60,'1y':365,'3y':1000
    };
    return limits[tf] || 500;
  }

  // Refresh interval in ms
  tfRefresh(tf: string): number {
    const intervals: Record<string,number> = {
      '1m':10000,'3m':15000,'5m':20000,'6m':30000,
      '15m':30000,'30m':60000,'1h':120000,'4h':300000,
      '1d':900000,'1w':3600000,'1y':3600000,'3y':3600000
    };
    return intervals[tf] || 30000;
  }

  getCryptoKlines(symbol: string, tf: string, limit?: number): Observable<any[]> {
    const apiTf  = this.TF_MAP[tf] || tf;
    const lim    = limit || this.tfLimit(tf);
    const refresh = this.tfRefresh(tf);
    return interval(refresh).pipe(
      startWith(0),
      switchMap(() =>
        this.http.get<any[]>(`${environment.binanceUrl}/api/v3/klines?symbol=${symbol}&interval=${apiTf}&limit=${lim}`)
          .pipe(catchError(() => of([])))
      ),
      map(raw => raw.map(k => ({
        time: Math.floor(k[0]/1000), open: parseFloat(k[1]),
        high: parseFloat(k[2]), low: parseFloat(k[3]),
        close: parseFloat(k[4]), volume: parseFloat(k[5]),
      })))
    );
  }

  getCryptoKlinesOnce(symbol: string, tf: string, limit = 500): Observable<any[]> {
    const apiTf = this.TF_MAP[tf] || tf;
    return this.http.get<any[]>(`${environment.binanceUrl}/api/v3/klines?symbol=${symbol}&interval=${apiTf}&limit=${limit}`)
      .pipe(
        catchError(() => of([])),
        map(raw => raw.map((k:any) => ({
          time: Math.floor(k[0]/1000), open: parseFloat(k[1]),
          high: parseFloat(k[2]), low: parseFloat(k[3]),
          close: parseFloat(k[4]), volume: parseFloat(k[5]),
        })))
      );
  }

  // ── FOREX: all pairs with search ──────────────────────────
  getForexLive(): Observable<LiveQuote[]> {
    return interval(60000).pipe(
      startWith(0),
      switchMap(() =>
        this.http.get<any>(`${environment.forexUrl}/latest?from=USD&to=INR,EUR,GBP,JPY,AUD,CAD,CHF,SGD,HKD,NZD,MXN,ZAR,NOK,SEK,DKK,AED,SAR,MYR`)
          .pipe(catchError(() => of(null)))
      ),
      map(data => this.buildFxQuotes(data?.rates || this.fxFallback(), !data?.rates))
    );
  }

  searchForexPairs(query: string): { sym: string; name: string; base: string; quote: string }[] {
    if (!query) return this.ALL_FOREX_PAIRS;
    const q = query.toLowerCase();
    return this.ALL_FOREX_PAIRS.filter(p =>
      p.sym.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)
    );
  }

  getForexKlines(base: string, quote: string): Observable<any[]> {
    // Use Yahoo Finance for proper OHLCV forex data (500 daily candles = ~2 years)
    const pair = base === 'USD' ? `${quote}=X` : `${base}${quote}=X`;
    return this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/${pair}?range=2y&interval=1d&lang=en-US`).pipe(
      catchError(() => of(null)),
      map((data: any) => {
        if (!data?.chart?.result?.[0]) return [];
        const r  = data.chart.result[0];
        const ts = r.timestamp || [];
        const q  = r.indicators?.quote?.[0] || {};
        return ts.map((t: number, i: number) => ({
          time: t, open: q.open?.[i]||0, high: q.high?.[i]||0,
          low: q.low?.[i]||0, close: q.close?.[i]||0, volume: q.volume?.[i]||1e9,
        })).filter((can: any) => can.close > 0);
      })
    );
  }

  // Review finding (P1 #8 — "The frontend still fabricates market data"): the isStale param is
  // the explicit fix — every quote gets stale:true when built from fxFallback() rather than a
  // real ECB rate. Separate, narrower issue found while fixing this, NOT addressed here: even
  // in the LIVE branch, change/high/low/open are still Math.random()-derived below (the ECB
  // rates endpoint only provides a single point-in-time rate per currency, no OHLC or change
  // data) — real price, synthetic derived fields. Distinct from what the review named (which was
  // about the base price itself being fake), and fixing it needs a different forex data source
  // with real OHLC, not something to fold into this stale-marking fix.
  // Review finding ("BUT — there is still fake/derived FX market data"): confirmed real — even
  // when the underlying rate came from a genuine ECB API response, change/high/low/open were
  // STILL Math.random()-derived on top of it. The review's own Option B: show only the actual
  // current rate; everything this data source genuinely doesn't provide (change/high/low/open —
  // the ECB rates endpoint is a single point-in-time rate per currency, no OHLC or change data
  // at all) is now honestly marked unavailable via ohlcUnavailable, not manufactured.
  private buildFxQuotes(rates: Record<string,number>, isStale: boolean = false): LiveQuote[] {
    return this.ALL_FOREX_PAIRS.map(p => {
      let price = 1;
      if      (p.base==='USD' && rates[p.quote]) price = rates[p.quote];
      else if (p.quote==='USD' && rates[p.base]) price = 1/rates[p.base];
      else if (rates[p.base] && rates[p.quote])  price = rates[p.quote]/rates[p.base];
      return { symbol:p.sym, name:p.name, price:+price.toFixed(6), change:0, changePct:0, high:price, low:price, open:price, volume:0, stale: isStale, ohlcUnavailable: true };
    });
  }

  // ── IPO — live, from backend, refetched every 5 min ─────────
  getIPOData(status: string = 'ALL'): Observable<IPOResponse> {
    return interval(300000).pipe(
      startWith(0),
      switchMap(() => this.http.get<any>(`${environment.apiUrl}/ipo?status=${status}`)
        .pipe(catchError(() => of(null)))),
      map(r => r?.data as IPOResponse ?? {
        ipos: [], total: 0, lastUpdated: 0, stale: true,
        warning: 'Could not reach server', gmpDisclaimer: ''
      })
    );
  }

  // Review finding (P1 #8 — "The frontend still fabricates market data"): every quote marked
  // stale:true — the review's own required signal, consumed by the components below to show a
  // visible "STALE / DEMO DATA" indicator rather than presenting these as current prices.
  indianFallback(): LiveQuote[] {
    return [
      {symbol:'RELIANCE', name:'Reliance Industries',sector:'Energy',   exchange:'NSE',price:2834,change:28,  changePct:1.0,  high:2865,low:2810,open:2806,volume:8423156},
      {symbol:'TCS',      name:'TCS',               sector:'IT',        exchange:'NSE',price:2125,change:-78, changePct:-3.55,high:2175,low:2090,open:2105,volume:2341890},
      {symbol:'HDFCBANK', name:'HDFC Bank',          sector:'Banking',   exchange:'NSE',price:1641,change:18,  changePct:1.11, high:1648,low:1620,open:1623,volume:12341567},
      {symbol:'INFY',     name:'Infosys',            sector:'IT',        exchange:'NSE',price:1471,change:-12, changePct:-0.81,high:1490,low:1465,open:1483,volume:5678234},
      {symbol:'ICICIBANK',name:'ICICI Bank',         sector:'Banking',   exchange:'NSE',price:1085,change:22,  changePct:2.07, high:1094,low:1062,open:1063,volume:18923456},
      {symbol:'SBIN',     name:'SBI',                sector:'Banking',   exchange:'NSE',price:812, change:13,  changePct:1.63, high:818, low:800, open:799, volume:23456789},
      {symbol:'WIPRO',    name:'Wipro',              sector:'IT',        exchange:'NSE',price:462, change:5,   changePct:1.09, high:468, low:455, open:457, volume:7654321},
      {symbol:'BAJFINANCE',name:'Bajaj Finance',     sector:'Finance',   exchange:'NSE',price:6834,change:98,  changePct:1.45, high:6890,low:6780,open:6736,volume:3456789},
      {symbol:'TATAMOTORS',name:'Tata Motors',       sector:'Auto',      exchange:'NSE',price:678, change:14,  changePct:2.11, high:685, low:665, open:664, volume:9876543},
      {symbol:'SUNPHARMA',name:'Sun Pharma',         sector:'Pharma',    exchange:'NSE',price:1698,change:-8,  changePct:-0.47,high:1715,low:1690,open:1706,volume:2345678},
      {symbol:'MARUTI',   name:'Maruti Suzuki',      sector:'Auto',      exchange:'NSE',price:12340,change:220,changePct:1.81, high:12480,low:12180,open:12120,volume:456789},
      {symbol:'ONGC',     name:'ONGC',               sector:'Energy',    exchange:'NSE',price:263, change:4,   changePct:1.54, high:267, low:259, open:259, volume:34567890},
      {symbol:'NTPC',     name:'NTPC',               sector:'Power',     exchange:'NSE',price:360, change:7,   changePct:1.98, high:363, low:353, open:353, volume:15678901},
      {symbol:'ADANIENT', name:'Adani Enterprises',  sector:'Conglomerate',exchange:'NSE',price:2456,change:-50,changePct:-2.0,high:2520,low:2430,open:2506,volume:4567890},
      {symbol:'HINDUNILVR',name:'HUL',               sector:'FMCG',      exchange:'NSE',price:2390,change:-9,  changePct:-0.38,high:2408,low:2380,open:2399,volume:1234567},
      {symbol:'ITC',      name:'ITC Ltd',            sector:'FMCG',      exchange:'NSE',price:448, change:3,   changePct:0.67, high:452, low:445, open:445, volume:18234567},
      {symbol:'LT',       name:'Larsen & Toubro',    sector:'Infrastructure',exchange:'NSE',price:3456,change:45,changePct:1.32,high:3490,low:3420,open:3411,volume:2134567},
      {symbol:'AXISBANK', name:'Axis Bank',          sector:'Banking',   exchange:'NSE',price:1156,change:18,  changePct:1.58, high:1165,low:1138,open:1138,volume:9876543},
      {symbol:'KOTAKBANK',name:'Kotak Mahindra Bank',sector:'Banking',   exchange:'NSE',price:1978,change:-22, changePct:-1.1, high:2005,low:1965,open:2000,volume:4123456},
      {symbol:'BHARTIARTL',name:'Bharti Airtel',     sector:'Telecom',   exchange:'NSE',price:1678,change:34,  changePct:2.07, high:1690,low:1645,open:1644,volume:6234567},
    ].map(q => ({...q, stale: true}));
  }

  // Review finding (P1 #8 — full context above): cryptoFallback() was dead code — defined but
  // never called anywhere in this file. Removed rather than left as an unused temptation for a
  // future call site to wire in without the stale marking this pass is adding everywhere else.

  private fxFallback(): Record<string,number> {
    return {INR:83.42,EUR:0.9234,GBP:0.7891,JPY:151.23,AUD:1.5234,CAD:1.3621,CHF:0.8934,SGD:1.3445,HKD:7.8234,NZD:1.6234,MXN:17.15,ZAR:18.72,NOK:10.54,SEK:10.41,DKK:6.89,AED:3.67,SAR:3.75,MYR:4.71};
  }

}
