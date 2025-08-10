import { registerPlugin } from '@capacitor/core';

import type { StreamHttpPlugin } from './definitions';

const StreamHttp = registerPlugin<StreamHttpPlugin>('StreamHttp', {
  web: () => import('./web').then((m) => new m.StreamHttpWeb()),
});

export * from './definitions';
export { StreamHttp };