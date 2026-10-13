package dev.ryvrook.atlas.markers;

import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.data.Payloads;

import java.util.Map;

/** The marker layer routes, api/layers/{world}. */
public final class MarkerRoutes {
    private final Atlas atlas;
    private final MarkerService markers;

    public MarkerRoutes(Atlas atlas, MarkerService markers) {
        this.atlas = atlas;
        this.markers = markers;
    }

    /**
     * Answers a marker route, or returns null when the path is not one. path is the decoded path after
     * /api/, such as layers/default.
     */
    public Atlas.ApiResult handle(String method, String path, Viewer viewer, Map<String, String> query) {
        if (path == null) {
            return null;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (!p.equals("layers") && !p.startsWith("layers/")) {
            return null;
        }
        if (method != null && !"GET".equals(method) && !"HEAD".equals(method)) {
            return new Atlas.ApiResult(405, Payloads.error("Only GET is allowed here."));
        }
        String world = p.length() > 7 ? p.substring(7) : "";
        if (world.isBlank()) {
            return new Atlas.ApiResult(400, Payloads.error("Name a world."));
        }
        if (world.contains("/") || atlas.hiddenWorld(viewer, world) || !atlas.worldExists(world)) {
            return new Atlas.ApiResult(404, Payloads.error("No world with that name."));
        }
        if (!viewer.canSee(atlas.config(), LayerJson.LAYER_KEY)) {
            return new Atlas.ApiResult(403, Payloads.error("This layer is not visible to you."));
        }
        return new Atlas.ApiResult(200, markers.layersPayload(viewer, world));
    }
}
