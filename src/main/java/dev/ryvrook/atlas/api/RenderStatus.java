package dev.ryvrook.atlas.api;

import java.util.Map;

/**
 * The tile render queue at one moment, from {@link AtlasApi#renderStatus()}. Plain values, so a caller can keep
 * it after Atlas stops. Not in Atlas 1.7.0 and older.
 *
 * @param queued          tiles waiting for a background render
 * @param running         renders under way now
 * @param done            renders finished since Atlas started
 * @param paused          true while /atlas render pause holds the queue
 * @param queuedPerWorld  queued tiles by world name, worlds with none left out
 * @param budgetLeft      chunk images left in the shared budget this minute, -1 when the budget is unlimited
 * @param budgetPerMinute chunk images the budget allows a minute, from chunkImagesPerMinute
 */
public record RenderStatus(int queued, int running, long done, boolean paused, Map<String, Integer> queuedPerWorld,
                           int budgetLeft, int budgetPerMinute) {
    public RenderStatus {
        queuedPerWorld = queuedPerWorld == null ? Map.of() : Map.copyOf(queuedPerWorld);
    }
}
