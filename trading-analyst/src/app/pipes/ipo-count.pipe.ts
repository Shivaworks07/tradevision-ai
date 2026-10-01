import { Pipe, PipeTransform } from '@angular/core';
import { IPOItem } from '../services/live-data.service';

@Pipe({ name: 'ipoCount', standalone: true })
export class IpoCountPipe implements PipeTransform {
  transform(ipos: IPOItem[], status: string): number {
    return ipos.filter(i => i.status === status).length;
  }
}
