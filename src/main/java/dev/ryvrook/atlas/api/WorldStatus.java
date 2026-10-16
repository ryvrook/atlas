package dev.ryvrook.atlas.api;

/**
 * One world Atlas draws, from {@link AtlasApi#worlds()}. Plain values. Not in Atlas 1.7.0 and older.
 *
 * @param name           the world name the map and its addresses use
 * @param loaded         true while the server has the world loaded
 * @param players        players in the world at the last refresh, 0 when it is not loaded
 * @param exploredChunks chunks that exist and get tiles, as of the last explored refresh, 0 before the first one
 * @param queuedTiles    tiles of this world waiting in the render queue
 * @param publicVisible  false when the world is closed to viewers who are not admins, by its hidden setting in
 *                       atlas.json or as a portal or instance world while showPortalWorlds is off
 */
public record WorldStatus(String name, boolean loaded, int players, int exploredChunks, int queuedTiles,
                          boolean publicVisible) {
}
