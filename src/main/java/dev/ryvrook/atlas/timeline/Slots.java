package dev.ryvrook.atlas.timeline;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Snapshot slots. The timeline takes timeline.perDay snapshots a day at evenly spaced hours starting
 * from timeline.hour, and each one is a slot named by its date and hour, such as 2026-10-13T16. A
 * slot folder holds what a day folder held before. A plain date such as 2026-10-13 is still a valid
 * slot. It is how the day folders of Atlas 1.0 and 1.1 are named. Wherever a slot is read, a plain
 * date stands for its own folder when that holds a snapshot, and otherwise for the last slot held
 * that day.
 */
public final class Slots {
    /** The values timeline.perDay accepts, each a divisor of 24. */
    public static final List<Integer> PER_DAY = List.of(1, 2, 3, 4, 6, 8, 12, 24);

    private Slots() {
    }

    /** True for a date such as 2026-10-13 or a date and hour such as 2026-10-13T16. */
    public static boolean valid(String s) {
        if (s == null) {
            return false;
        }
        if (s.length() == 10) {
            return validDate(s);
        }
        if (s.length() != 13 || s.charAt(10) != 'T' || !validDate(s.substring(0, 10))) {
            return false;
        }
        char a = s.charAt(11);
        char b = s.charAt(12);
        if (a < '0' || a > '2' || b < '0' || b > '9') {
            return false;
        }
        return (a - '0') * 10 + (b - '0') <= 23;
    }

    static boolean validDate(String s) {
        if (s == null || s.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** The date of a slot. */
    public static String date(String slot) {
        return slot.substring(0, 10);
    }

    /** The hour of a slot, or -1 for a plain date. */
    public static int hour(String slot) {
        return slot.length() == 13 ? Integer.parseInt(slot.substring(11)) : -1;
    }

    /** The slot of a date and an hour. */
    public static String id(String date, int hour) {
        return date + "T" + String.format(Locale.ROOT, "%02d", hour);
    }

    /** The hours of the slots of a day, earliest first, perDay of them evenly spaced from startHour. */
    public static int[] hours(int startHour, int perDay) {
        int n = PER_DAY.contains(perDay) ? perDay : 1;
        int step = 24 / n;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.floorMod(startHour + i * step, 24);
        }
        Arrays.sort(out);
        return out;
    }

    /**
     * The slot whose start is the latest one at or before t. Before the first slot hour of a day that
     * is the last slot of the day before.
     */
    public static String current(ZonedDateTime t, int startHour, int perDay) {
        int[] hours = hours(startHour, perDay);
        int best = -1;
        for (int h : hours) {
            if (h <= t.getHour()) {
                best = h;
            }
        }
        if (best < 0) {
            return id(t.toLocalDate().minusDays(1).toString(), hours[hours.length - 1]);
        }
        return id(t.toLocalDate().toString(), best);
    }

    /** When a slot starts, in the zone of t. */
    public static ZonedDateTime start(String slot, ZonedDateTime zoneOf) {
        int h = Math.max(0, hour(slot));
        return LocalDate.parse(date(slot)).atTime(h, 0).atZone(zoneOf.getZone());
    }

    /** The start of the first slot after t. */
    public static ZonedDateTime next(ZonedDateTime t, int startHour, int perDay) {
        int[] hours = hours(startHour, perDay);
        for (int h : hours) {
            if (h > t.getHour()) {
                return t.toLocalDate().atTime(h, 0).atZone(t.getZone());
            }
        }
        return t.toLocalDate().plusDays(1).atTime(hours[0], 0).atZone(t.getZone());
    }

    /** The hours as a sentence, such as 04:00, 10:00, 16:00 and 22:00. */
    public static String schedule(int[] hours) {
        List<String> parts = new ArrayList<>();
        for (int h : hours) {
            parts.add(String.format(Locale.ROOT, "%02d:00", h));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /**
     * The hours of the day whose events belong to a slot, from inclusive to exclusive. Every slot ends
     * where the next slot of the same day starts, and the last one at midnight. The first slot of a day
     * starts at midnight. A plain date, the day folder of Atlas 1.1 and older, counts as the earliest
     * slot of its day, so it takes the hours from midnight up to the first slot with an hour, or the
     * whole day when it is the only slot. Each event of the day therefore belongs to exactly one slot.
     * sameDay lists the slots held that day, in any order.
     */
    public static int[] window(String slot, List<String> sameDay) {
        int h = hour(slot);
        int to = 24;
        boolean earlier = false;
        for (String s : sameDay) {
            if (s == null || !valid(s) || !date(s).equals(date(slot))) {
                continue;
            }
            int o = hour(s);
            if (o > h && o < to) {
                to = o;
            } else if (o < h) {
                earlier = true;
            }
        }
        if (h < 0) {
            return new int[]{0, to};
        }
        return new int[]{earlier ? h : 0, to};
    }

    /** Orders slots by date and then hour, a plain date before the hours of its day. */
    public static int compare(String a, String b) {
        return a.compareTo(b);
    }
}
