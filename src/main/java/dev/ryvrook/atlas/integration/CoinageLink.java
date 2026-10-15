package dev.ryvrook.atlas.integration;

import java.util.UUID;

/** What the plugin keeps of the Coinage bridge, so it never names a Coinage type itself. */
public interface CoinageLink extends AutoCloseable {
    String GROUP = "Ryvrook";
    String NAME = "Coinage";
    String MINIMUM = "1.0.0";

    /** A player's money in the primary currency, formatted the way Coinage writes it in game. */
    record Money(String currency, String balance, String netWorth) {
    }

    /** The player's money, or null while Coinage is not running. Answered from Coinage's memory on any thread. */
    Money money(UUID player);

    /** True while the bridge holds the running Coinage API. */
    boolean bound();

    /** Drops the enable and disable hooks. Calling it twice is harmless. */
    @Override
    void close();
}
