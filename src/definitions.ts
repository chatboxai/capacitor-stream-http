import type { PluginListenerHandle } from '@capacitor/core';

export interface StartStreamOptions {
  /**
   * The URL to request
   */
  url: string;
  /**
   * HTTP method (GET, POST, etc.)
   */
  method: string;
  /**
   * Optional request headers
   */
  headers?: Record<string, string>;
  /**
   * Optional request body
   */
  body?: string;
  /**
   * Android connection establishment timeout in milliseconds as a non-negative integer.
   * Defaults to 90000. Set to 0 to disable the connect timeout.
   */
  connectTimeoutMillis?: number;
}

export interface StreamResponseEvent {
  id: string;
  status: number;
  headers: Record<string, string>;
}

export interface StreamChunkEvent {
  id: string;
  chunk: string;
}

export interface StreamEndEvent {
  id: string;
}

export interface StreamErrorEvent {
  id: string;
  error: string;
}

export interface StreamHttpEventMap {
  response: StreamResponseEvent;
  chunk: StreamChunkEvent;
  end: StreamEndEvent;
  error: StreamErrorEvent;
}

export interface StreamHttpPlugin {
  /**
   * Start a new HTTP stream request
   * @param options Stream configuration options
   * @returns Promise with stream ID
   */
  startStream(options: StartStreamOptions): Promise<{ id: string }>;

  /**
   * Cancel an active stream
   * @param options Object containing the stream ID to cancel
   * @returns Promise that resolves when stream is cancelled
   */
  cancelStream(options: { id: string }): Promise<void>;

  /**
   * Add a listener for stream events
   * @param eventName The event to listen for
   * @param listenerFunc Callback function for the event
   * @returns Promise with remove function
   */
  addListener<EventName extends keyof StreamHttpEventMap>(
    eventName: EventName,
    listenerFunc: (data: StreamHttpEventMap[EventName]) => void,
  ): Promise<PluginListenerHandle>;
}
