import { WebPlugin } from '@capacitor/core';

import type { StreamHttpPlugin, StartStreamOptions } from './definitions';

export class StreamHttpWeb extends WebPlugin implements StreamHttpPlugin {
  async startStream(_options: StartStreamOptions): Promise<{ id: string }> {
    console.warn('StreamHttp plugin is not implemented on web');
    throw new Error('StreamHttp plugin is not implemented on web');
  }

  async cancelStream(_options: { id: string }): Promise<void> {
    console.warn('StreamHttp plugin is not implemented on web');
    throw new Error('StreamHttp plugin is not implemented on web');
  }
}