package dev.ryvrook.atlas.integration;

import com.google.gson.JsonObject;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A linked player sees their own Coinage money, and only while showOwnMoney is on. */
class OwnMoneyTest {
    @TempDir
    Path dir;
    private Atlas atlas;
    private final List<UUID> asked = new ArrayList<>();

    private final CoinageLink fake = new CoinageLink() {
        @Override
        public Money money(UUID player) {
            asked.add(player);
            return new Money("coins", "$12.50", "$40.00");
        }

        @Override
        public boolean bound() {
            return true;
        }

        @Override
        public void close() {
        }
    };

    private Atlas atlas(boolean show) {
        AtlasConfig cfg = new AtlasConfig();
        cfg.showOwnMoney = show;
        cfg.dominionSnapshot = dir.resolve("none.json").toString();
        atlas = new Atlas(cfg, dir.resolve("data"));
        atlas.useCoinage(fake);
        return atlas;
    }

    @AfterEach
    void stop() {
        if (atlas != null) {
            atlas.tiles().shutdown();
        }
    }

    @Test
    void offByDefault() {
        assertFalse(new AtlasConfig().showOwnMoney);
        Atlas a = atlas(false);
        Viewer me = new Viewer(UUID.randomUUID(), "Ryv", null, false, false);
        assertFalse(a.moneyPayload(me).get("available").getAsBoolean());
        assertFalse(a.viewerJson(me).get("money").getAsBoolean());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aLinkedPlayerGetsTheirOwnMoneyOnly() {
        Atlas a = atlas(true);
        UUID uuid = UUID.randomUUID();
        Viewer me = new Viewer(uuid, "Ryv", null, false, false);
        JsonObject o = a.moneyPayload(me);
        assertTrue(o.get("available").getAsBoolean());
        assertEquals("$12.50", o.get("balance").getAsString());
        assertEquals("$40.00", o.get("netWorth").getAsString());
        assertEquals(List.of(uuid), asked);
        assertTrue(a.viewerJson(me).get("money").getAsBoolean());

        assertFalse(a.moneyPayload(Viewer.PUBLIC).get("available").getAsBoolean());
        Viewer admin = new Viewer(null, "admin", null, true, false);
        assertFalse(a.moneyPayload(admin.as("ABC")).get("available").getAsBoolean());
        assertFalse(a.moneyPayload(admin).get("available").getAsBoolean());
        assertEquals(List.of(uuid), asked);
    }

    @Test
    void withoutCoinageNothingIsOffered() {
        Atlas a = atlas(true);
        a.useCoinage(null);
        Viewer me = new Viewer(UUID.randomUUID(), "Ryv", null, false, false);
        assertFalse(a.viewerJson(me).get("money").getAsBoolean());
        assertFalse(a.moneyPayload(me).get("available").getAsBoolean());
    }
}
