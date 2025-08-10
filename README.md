# capacitor-stream-http

Capacitor plugin for native HTTP streaming support on iOS and Android. This plugin enables true streaming of HTTP responses, particularly useful for Server-Sent Events (SSE) and other streaming APIs.

## Features

- ✅ True HTTP streaming support (not buffered)
- ✅ Server-Sent Events (SSE) support
- ✅ Proper chunk-by-chunk data delivery
- ✅ Request cancellation support
- ✅ Custom headers and request body
- ✅ Works with proxied requests on mobile

## Install

```bash
npm install capacitor-stream-http
npx cap sync
```

## Usage

### Basic Usage

```typescript
import { StreamHttp } from 'capacitor-stream-http';

// Listen for chunks
await StreamHttp.addListener('chunk', (data) => {
  console.log('Received chunk:', data.chunk);
});

// Listen for stream end
await StreamHttp.addListener('end', (data) => {
  console.log('Stream ended');
});

// Listen for errors
await StreamHttp.addListener('error', (data) => {
  console.error('Stream error:', data.error);
});

// Start streaming
const { id } = await StreamHttp.startStream({
  url: 'https://api.example.com/stream',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer token'
  },
  body: JSON.stringify({ query: 'Hello' })
});

// Cancel stream if needed
await StreamHttp.cancelStream({ id });
```

### Using with ReadableStream API

```typescript
import { createNativeReadableStream } from 'capacitor-stream-http';

const stream = createNativeReadableStream({
  url: 'https://api.example.com/stream',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({ data: 'test' })
});

const reader = stream.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  
  const chunk = decoder.decode(value);
  console.log('Chunk:', chunk);
}
```

## API

### startStream(options)

Starts a new HTTP stream request.

**Parameters:**
- `url` (string): The URL to request
- `method` (string): HTTP method (GET, POST, etc.)
- `headers` (object): Optional request headers
- `body` (string): Optional request body

**Returns:** Promise<{ id: string }> - The stream ID

### cancelStream(options)

Cancels an active stream.

**Parameters:**
- `id` (string): The stream ID to cancel

### Events

- `chunk`: Fired when a data chunk is received
  - `id` (string): Stream ID
  - `chunk` (string): The data chunk
  
- `end`: Fired when the stream ends
  - `id` (string): Stream ID
  
- `error`: Fired on stream error
  - `id` (string): Stream ID
  - `error` (string): Error message

## Platform Support

- ✅ iOS (13.0+)
- ✅ Android (API 22+)
- ❌ Web (not supported - fallback to fetch API recommended)

## Implementation Details

### iOS
- Uses `URLSession` with delegate for streaming
- Supports HTTP/2 and HTTP/3
- Automatic retry and connection management

### Android
- Uses `HttpURLConnection` with chunked streaming mode
- SSE-aware parsing for proper event boundaries
- Thread-safe connection management

## License

MIT