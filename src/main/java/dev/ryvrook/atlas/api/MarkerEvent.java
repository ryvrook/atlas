package dev.ryvrook.atlas.api;

import java.util.UUID;

/**
 * One change to the shared markers of a world, as handed to {@link AtlasApi#onMarker}. Since API version 3.
 *
 * <p>player is the author of the marker, the player who placed it on the in game map, or null for a server
 * marker placed through /atlas marker and for a marker without a known author. A REMOVED event carries the
 * same author as the ADDED event of that marker, not the player who removed it. world is the world name,
 * layer is {@code shared} for the markers players place on the in game map and {@code atlas:admin} for the
 * server markers, markerId is the id Atlas shows the browser, x and z are the block position, or the centre
 * of an area, and at is the time in epoch milliseconds.
 */
public record MarkerEvent(Kind kind, UUID player, String world, String layer, String markerId, double x, double z,
                          long at) {
    /** What happened to the marker. */
    public enum Kind {
        ADDED, REMOVED
    }
}
