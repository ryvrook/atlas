package dev.ryvrook.atlas.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KillLogTest {
    @TempDir
    Path dir;

    @Test
    void thePlaceOfADeathIsSavedAndOlderLinesStillLoad() throws Exception {
        Files.writeString(dir.resolve("kills.json"), "[{\"at\":5,\"world\":\"default\",\"victim\":{\"name\":\"Bob\","
                + "\"type\":\"Player\",\"player\":true,\"boss\":false,\"uuid\":null},\"cause\":\"fall\",\"ranged\":false}]");
        KillLog log = new KillLog(dir);
        log.load();
        List<KillLog.Kill> old = log.recent(10);
        assertEquals(1, old.size());
        assertNull(old.get(0).x());
        assertNull(old.get(0).z());
        log.add(new KillLog.Kill(6, "default", new KillLog.Party("Ann", "Player", true, false, UUID.randomUUID()), null,
                "fall", false, 120, -45));
        log.save();
        KillLog again = new KillLog(dir);
        again.load();
        List<KillLog.Kill> back = again.recent(10);
        assertEquals(2, back.size());
        assertNull(back.get(0).x());
        assertEquals(120, back.get(1).x());
        assertEquals(-45, back.get(1).z());
    }
}
