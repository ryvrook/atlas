package dev.ryvrook.atlas.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The last pass over a markers payload, which makes sure no marker is listed twice. Pure, so it can
 * be checked without a running server. It runs after the privacy and map mod filters, so a hidden
 * entry can never take the place of a visible one.
 *
 * <p>Every entry has a rank and lower ranks win. Shared markers from the world store come first,
 * then the viewer's own personal markers, homes and deaths, then the world spawn, then markers read
 * from an engine store (points of interest, warps, block markers), then markers that came through a
 * provider. Two entries are the same marker when
 * <ul>
 * <li>their ids match,</li>
 * <li>a provider entry lies within one block of a store entry with the same name ignoring case,
 * which is one engine record reached twice,</li>
 * <li>or a personal marker of the viewer lies within one block of a shared marker the viewer placed,
 * with the same name ignoring case. The shared entry stays and is marked own.</li>
 * </ul>
 * A null name only matches a null name. Entries of the same rank are never merged by position, so
 * two players' markers, two beds or two deaths side by side all stay. A home swallows a store or
 * provider entry within one block of it that is a bed, which is one with the Home icon, a name with
 * "bed" in it or the home's own name. A provider entry whose id starts with user_ is a player marker
 * that the store reads already cover, so it is always dropped.
 *
 * <p>For shared entries, nameKeys holds the creator's uuid as a string, or nothing when unknown.
 */
public final class MarkerMerge {
    public static final int RANK_SHARED = 0;
    public static final int RANK_OWN = 1;
    public static final int RANK_SPAWN = 2;
    public static final int RANK_STORE = 3;
    public static final int RANK_PROVIDER = 4;

    /** One entry of the payload with its rank. source and nameKeys are used by the filters. */
    public record Candidate(JsonObject json, int rank, String source, List<String> nameKeys) {
    }

    private MarkerMerge() {
    }

    /** The entries without duplicates, in the order they were given. viewer may be null. */
    public static List<Candidate> dedupe(List<Candidate> in, UUID viewer) {
        String me = viewer == null ? null : viewer.toString();
        List<Integer> order = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            order.add(i);
        }
        // Stable, so entries of one rank keep their order.
        order.sort((a, b) -> Integer.compare(in.get(a).rank(), in.get(b).rank()));
        boolean[] keep = new boolean[in.size()];
        Map<String, Integer> byId = new HashMap<>();
        Map<Long, List<Integer>> byCell = new HashMap<>();
        for (int i : order) {
            Candidate c = in.get(i);
            String id = str(c.json(), "id");
            String kind = str(c.json(), "kind");
            if (c.rank() == RANK_PROVIDER && id != null && id.startsWith("user_")) {
                continue;
            }
            Integer winner = id == null ? null : byId.get(id);
            Integer x = num(c.json(), "x");
            Integer z = num(c.json(), "z");
            if (winner == null && x != null && z != null) {
                winner = near(in, byCell, c, kind, x, z, me);
            }
            if (winner != null) {
                Candidate w = in.get(winner);
                if (w.rank() == RANK_SHARED && "personal".equals(kind) && me != null && w.nameKeys().contains(me)) {
                    w.json().addProperty("own", true);
                }
                continue;
            }
            keep[i] = true;
            if (id != null) {
                byId.put(id, i);
            }
            if (x != null && z != null) {
                byCell.computeIfAbsent(cell(x, z), k -> new ArrayList<>(1)).add(i);
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            if (keep[i]) {
                out.add(in.get(i));
            }
        }
        return out;
    }

    /** The kept entry within one block that this candidate is a copy of, or null. */
    private static Integer near(List<Candidate> in, Map<Long, List<Integer>> byCell, Candidate c, String kind,
                                int x, int z, String me) {
        String name = lower(str(c.json(), "name"));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<Integer> here = byCell.get(cell(x + dx, z + dz));
                if (here == null) {
                    continue;
                }
                for (int k : here) {
                    Candidate w = in.get(k);
                    JsonObject o = w.json();
                    boolean sameName = java.util.Objects.equals(name, lower(str(o, "name")));
                    if (sameName && c.rank() == RANK_PROVIDER && w.rank() == RANK_STORE) {
                        return k;
                    }
                    if (sameName && "personal".equals(kind) && w.rank() == RANK_SHARED && me != null
                            && w.nameKeys().contains(me)) {
                        return k;
                    }
                    if (c.rank() >= RANK_STORE && "home".equals(str(o, "kind")) && isBed(c.json(), lower(str(o, "name")))) {
                        return k;
                    }
                }
            }
        }
        return null;
    }

    /** True when a store or provider entry looks like the bed of a home with the given name. */
    private static boolean isBed(JsonObject o, String homeName) {
        String icon = str(o, "icon");
        String name = lower(str(o, "name"));
        return "Home".equalsIgnoreCase(icon) || (name != null && (name.contains("bed") || name.equals(homeName)));
    }

    private static long cell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Integer num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
