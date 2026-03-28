package io.legate.core.provider;

import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context for accumulating streaming chunks and reconstructing final usage.
 * Used during SSE streaming to collect chunks and extract usage information.
 */
public class StreamContext {
    private final List<ChatCompletionChunk> chunks = new ArrayList<>();
    private final StringBuilder contentBuilder = new StringBuilder();
    private Usage usage;
    private String finishReason;

    /**
     * Adds a chunk to the context.
     */
    public void addChunk(ChatCompletionChunk chunk) {
        chunks.add(chunk);

        // Extract content delta
        String delta = chunk.getDeltaContent();
        if (delta != null) {
            contentBuilder.append(delta);
        }

        // Extract usage (typically comes in the last chunk)
        if (chunk.usage() != null) {
            this.usage = chunk.usage();
        }

        // Extract finish reason
        if (chunk.isFinished() && chunk.choices() != null && !chunk.choices().isEmpty()) {
            this.finishReason = chunk.choices().get(0).finishReason();
        }
    }

    /**
     * Returns all accumulated chunks.
     */
    public List<ChatCompletionChunk> getChunks() {
        return List.copyOf(chunks);
    }

    /**
     * Returns the complete content accumulated from all deltas.
     */
    public String getAccumulatedContent() {
        return contentBuilder.toString();
    }

    /**
     * Returns the usage information (may be null if not provided in stream).
     */
    public Usage getUsage() {
        return usage;
    }

    /**
     * Sets the usage information.
     */
    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Returns the finish reason.
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Returns true if the stream has finished.
     */
    public boolean isFinished() {
        return finishReason != null;
    }
}
