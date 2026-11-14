package com.jack.shelf;

import android.util.Xml;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Fetches Bondi weather (Open-Meteo, no key needed) and ABC News headlines. Call off the UI thread. */
final class Glance {

    static final class Weather {
        int temp, hi, lo, code;
        boolean isDay = true;
        String condition;
    }

    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=-33.8915&longitude=151.2767"
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=Australia%2FSydney&forecast_days=1";

    private static final String NEWS_URL = "https://www.abc.net.au/news/feed/51120/rss.xml";

    private Glance() {
    }

    static Weather weather() {
        try {
            JSONObject o = new JSONObject(get(WEATHER_URL));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            Weather w = new Weather();
            w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            w.code = cur.getInt("weather_code");
            w.condition = describe(w.code);
            w.isDay = cur.optInt("is_day", 1) == 1;
            w.hi = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            w.lo = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> headlines() {
        List<String> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(get(NEWS_URL)));
            boolean inItem = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT && out.size() < 12) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("item".equals(n)) inItem = true;
                    else if (inItem && "title".equals(n)) {
                        String t = p.nextText().trim();
                        if (t.startsWith("Live: ")) t = t.substring(6);
                        if (!t.isEmpty()) out.add(t);
                    }
                } else if (ev == XmlPullParser.END_TAG && "item".equals(p.getName())) {
                    inItem = false;
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Shelf/1.0 (Android TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes → plain words. */
    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }
}
