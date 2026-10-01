import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReplayComponent } from './replay.component';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';

describe('ReplayComponent', () => {
  let component: ReplayComponent;
  let fixture: ComponentFixture<ReplayComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReplayComponent, HttpClientTestingModule, RouterTestingModule]
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
    // Review finding ("Frontend test coverage" -- P2): this test's own body contradicted its
    // own name -- it asserted stepping from 60 down through 59 to 58 would succeed, which is
    // literally stepping BELOW the minimum the test's own name says shouldn't happen. Read the
    // real component logic before "fixing" this: stepBack()'s guard (`if (this.currentIdx <=
    // 60) return;`) is correct and intentional, matching this component's own separate,
    // documented requirement elsewhere that at least 60 candles are needed for real analysis
    // (`if (candles.length < 60) return;`) -- 60 is a genuine floor, not an off-by-one bug.
    // Fixed to actually test what the name says: stepping back from above the floor works
    // normally, and stepping AT the floor is correctly blocked.
    component['currentIdx'] = 65;
    component.stepBack();
    expect(component['currentIdx']).toBe(64);

    component['currentIdx'] = 60;
    component.stepBack();
    expect(component['currentIdx']).toBe(60); // blocked -- must not go below the documented minimum
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
