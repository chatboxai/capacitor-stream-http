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
   * @param eventName The event to listen for (chunk, end, or error)
   * @param listenerFunc Callback function for the event
   * @returns Promise with remove function
   */
  addListener(
    eventName: 'chunk' | 'end' | 'error',
    listenerFunc: (data: { id: string; chunk?: string; error?: string }) => void
  ): Promise<{ remove: () => void }>;
}