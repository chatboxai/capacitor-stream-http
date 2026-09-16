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
  /** Redirect policy. Defaults to follow. error rejects before contacting the redirect target. */
  redirect?: 'follow' | 'error';
}

export interface StreamResponseEvent {
  id: string;
  /** HTTP status code of the response. */
  status: number;
  /** Response headers with lowercase names. Repeated headers are joined with ", ". */
  headers: Record<string, string>;
}

export interface StreamChunkEvent {
  id: string;
  chunk?: string;
}

export interface StreamEndEvent {
  id: string;
}

export interface StreamErrorEvent {
  id: string;
  error?: string;
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
   * Add a listener for stream events.
   *
   * `response` fires once, before the first `chunk`, with the HTTP status and headers.
   * @param eventName The event to listen for (response, chunk, end, or error)
   * @param listenerFunc Callback function for the event
   * @returns Promise with remove function
   */
  addListener(
    eventName: 'response',
    listenerFunc: (data: StreamResponseEvent) => void,
  ): Promise<{ remove: () => void }>;
  addListener(eventName: 'chunk', listenerFunc: (data: StreamChunkEvent) => void): Promise<{ remove: () => void }>;
  addListener(eventName: 'end', listenerFunc: (data: StreamEndEvent) => void): Promise<{ remove: () => void }>;
  addListener(eventName: 'error', listenerFunc: (data: StreamErrorEvent) => void): Promise<{ remove: () => void }>;
  addListener(
    eventName: 'chunk' | 'end' | 'error',
    listenerFunc: (data: StreamChunkEvent & StreamEndEvent & StreamErrorEvent) => void,
  ): Promise<{ remove: () => void }>;
}
