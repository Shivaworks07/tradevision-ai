import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WatchlistComponent } from './watchlist.component';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

describe('WatchlistComponent', () => {
  let component: WatchlistComponent;
  let fixture: ComponentFixture<WatchlistComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
    imports: [WatchlistComponent, RouterTestingModule],
    providers: [provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
}).compileComponents();
    fixture = TestBed.createComponent(WatchlistComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => expect(component).toBeTruthy());

  it('should show empty state when not logged in', () => {
    spyOnProperty(component.auth, 'isLoggedIn', 'get').and.returnValue(false);
    expect(component.items.length).toBe(0);
  });

  it('confColor should return green for high confidence', () => {
    expect(component.confColor(80)).toBe('#00FF88');
  });

  it('confColor should return red for low confidence', () => {
    expect(component.confColor(30)).toBe('#FF3B5C');
  });

  it('priceStr should format crypto price correctly', () => {
    // priceStr is deliberately multi-currency: prices >= 1000 format in INR (the app's
    // home-currency display for larger/whole-coin values), while smaller fractional
    // crypto prices, quoted globally in USD, stay in USD.
    expect(component.priceStr(0.001234)).toContain('$');
    expect(component.priceStr(50000)).toContain('₹');
  });

  it('sigClass should return bull for LONG direction', () => {
    const mockCall = { direction: 'LONG' } as any;
    expect(component.sigClass(mockCall)).toBe('bull');
  });

  it('sigClass should return neutral for undefined call', () => {
    expect(component.sigClass(undefined)).toBe('neutral');
  });
});
