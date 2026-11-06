import type { MusicBridgeApi } from './types';

declare global {
  interface Window {
    musicbridge: MusicBridgeApi;
  }
}

export {};
