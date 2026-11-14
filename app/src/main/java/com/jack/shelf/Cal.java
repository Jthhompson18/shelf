package com.jack.shelf;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Reads a private iCal (.ics) link from Google Calendar or iCloud and lists today's and tomorrow's events. */
final class Cal {

    static final class Event implements Comparable<Event> {
        long start, end;
        boolean allDay;
        String title;

        @Override
        public int compareTo(Event o) {
            return Long.compare(start, o.start);
        }
    }

    private static final TimeZone SYD = TimeZone.getTimeZone("Australia/Sydney");

    private Cal() {
    }

    /** Events from the start of today until the end of tomorrow (Sydney time), sorted. Null on failure. */
    static List<Event> upcoming(String url) {
        try {
            if (url == null || url.isEmpty()) return null;
            if (url.startsWith("webcal://")) url = "https://" + url.substring(9);
            String ics = get(url);
            Calendar c = Calendar.getInstance(SYD);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long from = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 2);
            long to = c.getTimeInMillis();
            return parse(ics, from, to);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Event> parse(String ics, long from, long to) {
        // unfold folded lines
        String[] raw = ics.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> lines = new ArrayList<>();
        for (String l : raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + l.substring(1));
            } else {
                lines.add(l);
            }
        }
        List<Event> out = new ArrayList<>();
        boolean in = false;
        String summary = null, dtStart = null, dtEnd = null, rrule = null;
        Set<Long> exdates = new HashSet<>();
        for (String l : lines) {
            if (l.equals("BEGIN:VEVENT")) {
                in = true;
                summary = dtStart = dtEnd = rrule = null;
                exdates.clear();
            } else if (l.equals("END:VEVENT")) {
                in = false;
                try {
                    addOccurrences(out, summary, dtStart, dtEnd, rrule, exdates, from, to);
                } catch (Throwable ignored) {
                }
            } else if (in) {
                int colon = l.indexOf(':');
                if (colon < 0) continue;
                String key = l.substring(0, colon), val = l.substring(colon + 1);
                String name = key.contains(";") ? key.substring(0, key.indexOf(';')) : key;
                if (name.equals("SUMMARY")) summary = unescape(val);
                else if (name.equals("DTSTART")) dtStart = l;
                else if (name.equals("DTEND")) dtEnd = l;
                else if (name.equals("RRULE")) rrule = val;
                else if (name.equals("EXDATE")) {
                    for (String v : val.split(",")) {
                        try {
                            exdates.add(time(key + ":" + v)[0]);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Returns {millis, allDay(1/0)} for a DTSTART/DTEND line. */
    private static long[] time(String line) {
        int colon = line.lastIndexOf(':');
        String key = line.substring(0, colon), v = line.substring(colon + 1).trim();
        TimeZone tz = SYD;
        int tzi = key.indexOf("TZID=");
        if (tzi >= 0) {
            String id = key.substring(tzi + 5);
            int semi = id.indexOf(';');
            if (semi >= 0) id = id.substring(0, semi);
            id = id.replace("\"", "");
            TimeZone t = TimeZone.getTimeZone(id);
            if (t != null) tz = t;
        }
        boolean allDay = v.length() == 8;
        if (v.endsWith("Z")) {
            tz = TimeZone.getTimeZone("UTC");
            v = v.substring(0, v.length() - 1);
        }
        Calendar c = Calendar.getInstance(allDay ? SYD : tz);
        c.clear();
        c.set(Integer.parseInt(v.substring(0, 4)), Integer.parseInt(v.substring(4, 6)) - 1,
                Integer.parseInt(v.substring(6, 8)));
        if (!allDay) {
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(v.substring(9, 11)));
            c.set(Calendar.MINUTE, Integer.parseInt(v.substring(11, 13)));
            c.set(Calendar.SECOND, v.length() >= 15 ? Integer.parseInt(v.substring(13, 15)) : 0);
        }
        return new long[]{c.getTimeInMillis(), allDay ? 1 : 0};
    }

    private static void addOccurrences(List<Event> out, String summary, String dtStart, String dtEnd,
                                       String rrule, Set<Long> ex, long from, long to) {
        if (dtStart == null) return;
        long[] s = time(dtStart);
        long start = s[0];
        boolean allDay = s[1] == 1;
        long dur = dtEnd != null ? time(dtEnd)[0] - start : (allDay ? 86400000L : 3600000L);
        String title = summary == null || summary.isEmpty() ? "(No title)" : summary;

        if (rrule == null) {
            if (start < to && start + dur > from) out.add(ev(start, start + dur, allDay, title));
            return;
        }
        String freq = null, byday = null;
        int interval = 1, count = -1;
        long until = Long.MAX_VALUE;
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length < 2) continue;
            switch (kv[0]) {
                case "FREQ": freq = kv[1]; break;
                case "INTERVAL": interval = Math.max(1, Integer.parseInt(kv[1])); break;
                case "COUNT": count = Integer.parseInt(kv[1]); break;
                case "UNTIL": until = time("X:" + kv[1])[0] + (kv[1].length() == 8 ? 86400000L : 0); break;
                case "BYDAY": byday = kv[1]; break;
                default: break;
            }
        }
        if (freq == null) return;
        Calendar c = Calendar.getInstance(SYD);
        c.setTimeInMillis(start);
        int made = 0;
        String[] days = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
        for (int guard = 0; guard < 20000; guard++) {
            long t = c.getTimeInMillis();
            if (t >= to || t > until) break;
            boolean ok = true;
            if ("WEEKLY".equals(freq) && byday != null) {
                ok = byday.contains(days[c.get(Calendar.DAY_OF_WEEK) - 1]);
            }
            if (ok) {
                if (t >= start) {
                    made++;
                    if (count > 0 && made > count) break;
                    if (!ex.contains(t) && t + dur > from) out.add(ev(t, t + dur, allDay, title));
                }
            }
            switch (freq) {
                case "DAILY": c.add(Calendar.DAY_OF_MONTH, interval); break;
                case "WEEKLY":
                    if (byday != null) {
                        c.add(Calendar.DAY_OF_MONTH, 1);
                        // jump whole skipped weeks when interval > 1
                        if (interval > 1 && c.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
                            c.add(Calendar.WEEK_OF_YEAR, interval - 1);
                        }
                    } else {
                        c.add(Calendar.WEEK_OF_YEAR, interval);
                    }
                    break;
                case "MONTHLY": c.add(Calendar.MONTH, interval); break;
                case "YEARLY": c.add(Calendar.YEAR, interval); break;
                default: return;
            }
        }
    }

    private static Event ev(long s, long e, boolean allDay, String title) {
        Event x = new Event();
        x.start = s;
        x.end = e;
        x.allDay = allDay;
        x.title = title;
        return x;
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Shelf/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
