package com.chatbox.plugins.streamhttp;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple SSE (Server-Sent Events) parser
 */
public class SSEParser {
    private StringBuilder buffer = new StringBuilder();
    private List<String> currentEvent = new ArrayList<>();
    
    /**
     * Process a line of SSE data
     * @param line The line to process
     * @return Complete SSE event if one is ready, null otherwise
     */
    public String processLine(String line) {
        if (line == null) {
            return null;
        }
        
        // Empty line signals end of event
        if (line.isEmpty()) {
            if (!currentEvent.isEmpty()) {
                StringBuilder event = new StringBuilder();
                for (String eventLine : currentEvent) {
                    event.append(eventLine).append("\n");
                }
                event.append("\n"); // Add the empty line that marks event boundary
                currentEvent.clear();
                return event.toString();
            }
            return null;
        }
        
        // Add line to current event
        currentEvent.add(line);
        return null;
    }
    
    /**
     * Get any remaining data in the buffer
     * @return Remaining data or null if buffer is empty
     */
    public String flush() {
        if (!currentEvent.isEmpty()) {
            StringBuilder event = new StringBuilder();
            for (String eventLine : currentEvent) {
                event.append(eventLine).append("\n");
            }
            currentEvent.clear();
            return event.toString();
        }
        return null;
    }
    
    /**
     * Clear the parser state
     */
    public void clear() {
        buffer.setLength(0);
        currentEvent.clear();
    }
}