import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'symbolDisplay', standalone: true })
export class SymbolDisplayPipe implements PipeTransform {
  transform(symbols: any[], binanceSymbol: string): string {
    return symbols.find(s => s.symbol === binanceSymbol)?.display || binanceSymbol;
  }
}
