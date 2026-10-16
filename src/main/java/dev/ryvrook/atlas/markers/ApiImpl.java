package dev.ryvrook.atlas.markers;

import dev.ryvrook.atlas.api.AtlasApi;
import dev.ryvrook.atlas.api.MarkerEvent;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.RenderStatus;
import dev.ryvrook.atlas.api.WorldStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** The API object other plugins get from AtlasApi.get(). Everything is handed to the marker service. */
final class ApiImpl extends AtlasApi {
    /** Plugin ids Atlas keeps for its own layers. */
    static final Set<String> RESERVED = Set.of("atlas", "file");

    private final MarkerService service;
    private final LayerListener listener;

    ApiImpl(MarkerService service) {
        this.service = service;
        this.listener = service::changed;
    }

    /** A layer that reports its changes to nobody, for files that are parsed and then swapped in whole. */
    static MarkerLayer detached(String pluginId, String layerId) {
        return newLayer(pluginId, layerId, null);
    }

    /** A layer that reports its changes to the marker service. */
    MarkerLayer attached(String pluginId, String layerId) {
        return newLayer(pluginId, layerId, listener);
    }

    void up() {
        enable(this);
    }

    void down() {
        disable(this);
    }

    @Override
    public MarkerLayer layer(String pluginId, String layerId) {
        if (pluginId != null && RESERVED.contains(pluginId.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("the plugin ids atlas and file are kept for Atlas");
        }
        return service.apiLayer(pluginId, layerId);
    }

    @Override
    public List<MarkerLayer> layers() {
        return service.allLayers();
    }

    @Override
    public List<MarkerLayer> layers(String world) {
        return service.allLayers().stream().filter(l -> l.worlds().contains(world)).toList();
    }

    @Override
    public boolean removeLayer(String pluginId, String layerId) {
        return service.removeApiLayer(pluginId, layerId);
    }

    @Override
    public void setHidden(UUID player, boolean hidden, String reason) {
        service.setApiHidden(player, hidden, reason);
    }

    @Override
    public boolean isHidden(UUID player) {
        return service.isHidden(player);
    }

    @Override
    public String publicUrl() {
        return service.atlas().publicAddress();
    }

    @Override
    public boolean publicUrlSet() {
        return service.atlas().config().hasPublicUrl();
    }

    @Override
    public RenderStatus renderStatus() {
        return service.atlas().apiRenderStatus();
    }

    @Override
    public List<WorldStatus> worlds() {
        return service.atlas().apiWorlds();
    }

    @Override
    public Runnable onMarker(Consumer<MarkerEvent> listener) {
        return service.onMarker(listener);
    }
}
