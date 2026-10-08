import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReplayComponent } from './replay.component';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

describe('ReplayComponent', () => {
  let component: ReplayComponent;
  let fixture: ComponentFixture<ReplayComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
    imports: [ReplayComponent, RouterTestingModule],
    providers: [provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
}).compileComponents();
    fixture = TestBed.createComponent(ReplayComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => expect(component).toBeTruthy());

  it('should default to CRYPTO market', () => {
    expect(component.activeMarket).toBe('CRYPTO');
  });

  it('should switch market correctly', () => {
    component.setMarket('STOCK');
    expect(component.activeMarket).toBe('STOCK');
    expect(component.timeframe).toBe('1d');
    expect(component.loaded).toBeFalse();
  });

  it('should set speed correctly', () => {
    component.setSpeed(1); // 2×
    expect(component.speed).toBe(250);
    expect(component.activeSpeedIdx).toBe(1);
  });

  it('should compute win rate as 0 with no trades', () => {
    expect(component.winRate).toBe(0);
  });

  it('should compute win rate correctly', () => {
    (component as any).wins = 3;
    (component as any).losses = 1;
    expect(component.winRate).toBe(75);
  });

  it('should not step back below minimum candle index', () => {
    // stepBack() refuses to move below index 60, since the analysis logic
    // needs at least 60 candles of history to produce a meaningful result.
    component['currentIdx'] = 65;
    component.stepBack();
    expect(component['currentIdx']).toBe(64);

    component['currentIdx'] = 60;
    component.stepBack();
    expect(component['currentIdx']).toBe(60); // blocked -- must not go below the minimum
  });

  it('should export CSV only when history exists', () => {
    spyOn(document, 'createElement').and.callThrough();
    component.exportCSV(); // no history
    // Should not call createElement when no history
    expect(document.createElement).not.toHaveBeenCalled();
  });

  it('should handle keyboard Space for play/pause', () => {
    component.loaded = true;
    component['playing'] = false;
    const event = new KeyboardEvent('keydown', { code: 'Space' });
    component.onKey(event);
    // play should be triggered (timer started)
    expect(component['playing']).toBeTrue();
    component.stop();
  });
});
