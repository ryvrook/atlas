package dev.ryvrook.atlas.integration;

import dev.ryvrook.coinage.api.CoinageApi;
import dev.ryvrook.coinage.api.Currency;
import dev.ryvrook.coinage.api.NetWorth;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reads a player's balance and net worth from Coinage for the account menu. The only class of Atlas that
 * names a Coinage type. The plugin loads it only after the plugin manager reported Coinage, so Atlas still
 * loads on a server without it. Atlas only reads, it never moves money.
 */
public final class CoinageBridge implements CoinageLink {
    private volatile CoinageApi api;
    private Consumer<CoinageApi> enableHook;
    private Runnable disableHook;

    private CoinageBridge() {
    }

    /** Builds the bridge and hooks it to Coinage's enable and disable. Called from the plugin's start. */
    public static CoinageLink install() {
        CoinageBridge b = new CoinageBridge();
        b.enableHook = live -> b.api = live;
        b.disableHook = () -> b.api = null;
        CoinageApi.onEnable(b.enableHook);
        CoinageApi.onDisable(b.disableHook);
        return b;
    }

    @Override
    public Money money(UUID player) {
        CoinageApi a = api;
        if (a == null || player == null) {
            return null;
        }
        Currency c = a.currencies().primary();
        NetWorth worth = a.reports().netWorth(player, c.id());
        if (worth == null) {
            return null;
        }
        Long wallet = worth.parts().get("wallet");
        return new Money(c.id(), c.format(wallet == null ? 0 : wallet), c.format(worth.total()));
    }

    @Override
    public boolean bound() {
        return api != null;
    }

    @Override
    public synchronized void close() {
        CoinageApi.removeHooks(enableHook, disableHook);
        api = null;
    }
}
