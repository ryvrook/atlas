package dev.ryvrook.atlas.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Addresses and CIDR ranges of reverse proxies whose X-Forwarded-For header is believed. When the
 * direct peer is trusted, the client is the last address in the header that is not itself a trusted
 * proxy. Anything that is not a plain IP literal is ignored, so no lookup ever reaches DNS.
 */
public final class TrustedProxies {
    private record Range(byte[] network, int bits) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    public static final TrustedProxies NONE = new TrustedProxies(List.of(), List.of());

    private final List<Range> ranges;
    private final List<String> rejected;

    private TrustedProxies(List<Range> ranges, List<String> rejected) {
        this.ranges = ranges;
        this.rejected = rejected;
    }

    /** Parses entries such as 127.0.0.1, 10.0.0.0/8 or ::1. Entries that do not parse are kept in rejected. */
    public static TrustedProxies parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return NONE;
        }
        List<Range> ranges = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int slash = entry.indexOf('/');
            String host = slash < 0 ? entry : entry.substring(0, slash);
            byte[] addr = literal(host);
            if (addr == null) {
                rejected.add(entry);
                continue;
            }
            int bits = addr.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    rejected.add(entry);
                    continue;
                }
                if (bits < 0 || bits > addr.length * 8) {
                    rejected.add(entry);
                    continue;
                }
            }
            ranges.add(new Range(addr, bits));
        }
        return new TrustedProxies(List.copyOf(ranges), List.copyOf(rejected));
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public List<String> rejected() {
        return rejected;
    }

    public boolean trusts(String address) {
        byte[] addr = literal(address);
        if (addr == null) {
            return false;
        }
        for (Range r : ranges) {
            if (r.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The client address for a request. The peer is returned unless it is trusted and the request
     * carries X-Forwarded-For, in which case the header is walked from the right and the first
     * address that is not a trusted proxy wins. When every entry is trusted the leftmost one is used.
     */
    public String client(String peer, List<String> forwardedFor) {
        if (ranges.isEmpty() || peer == null || forwardedFor == null || forwardedFor.isEmpty() || !trusts(peer)) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String line : forwardedFor) {
            if (line == null) {
                continue;
            }
            for (String part : line.split(",")) {
                String hop = strip(part);
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        String leftmost = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (literal(hop) == null) {
                // A value that is not an address cannot be trusted further, so stop at the last good hop.
                break;
            }
            leftmost = hop;
            if (!trusts(hop)) {
                return hop;
            }
        }
        return leftmost != null ? leftmost : peer;
    }

    /** Removes brackets, ports and zone ids that proxies sometimes add to the header values. */
    static String strip(String raw) {
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s;
        }
        int colon = s.indexOf(':');
        if (colon > 0 && colon == s.lastIndexOf(':') && s.indexOf('.') > 0) {
            // IPv4 with a port.
            return s.substring(0, colon);
        }
        return s;
    }

    /** The bytes of an IPv4 or IPv6 literal, or null for anything else. Never performs a lookup. */
    static byte[] literal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean colon = s.indexOf(':') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || (colon && (c == ':' || (c >= 'a' && c <= 'f')));
            if (!ok) {
                return null;
            }
        }
        if (!colon) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return null;
                }
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }
        try {
            // Only hex digits, dots and colons reach this point, so the call parses and never resolves.
            // An IPv4 mapped IPv6 address comes back as the four bytes of the IPv4 address it carries.
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }
}
