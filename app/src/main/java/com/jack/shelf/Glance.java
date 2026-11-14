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
        int wind = -1, windDir;
        boolean isDay = true;
        String condition;
    }

    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=-33.8915&longitude=151.2767"
                    + "&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=Australia%2FSydney&forecast_days=1";

    private static final String NEWS_URL = "https://www.abc.net.au/news/feed/51120/rss.xml";

    private Glance() {
    }

    static final class Day {
        final String[] hTime = new String[6];
        final int[] hTemp = new int[6], hRain = new int[6], hCode = new int[6];
        int n, hi, lo, code, rainMax;
        double uv;
        String sunrise, sunset;
        double dawnWave = Double.NaN;
        int dawnDir;
        boolean dawnTomorrow;
    }

    /** Fuller forecast for the Today screen. */
    static Day day() {
        Day d = new Day();
        try {
            JSONObject o = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=-33.8915&longitude=151.2767"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability"
                    + "&daily=sunrise,sunset,uv_index_max,temperature_2m_max,temperature_2m_min,"
                    + "precipitation_probability_max,weather_code"
                    + "&timezone=Australia%2FSydney&forecast_days=2"));
            JSONObject hr = o.getJSONObject("hourly");
            org.json.JSONArray t = hr.getJSONArray("time");
            String nowKey = MainActivity.fmt("yyyy-MM-dd'T'HH:00", System.currentTimeMillis());
            int idx = 0;
            for (int i = 0; i < t.length(); i++) {
                if (t.getString(i).compareTo(nowKey) >= 0) {
                    idx = i;
                    break;
                }
            }
            for (int k = 0; k < 6; k++) {
                int i = idx + k * 2;
                if (i >= t.length()) break;
                d.hTime[k] = t.getString(i).substring(11, 16);
                d.hTemp[k] = (int) Math.round(hr.getJSONArray("temperature_2m").getDouble(i));
                d.hRain[k] = hr.getJSONArray("precipitation_probability").optInt(i, 0);
                d.hCode[k] = hr.getJSONArray("weather_code").optInt(i, 0);
                d.n = k + 1;
            }
            JSONObject dy = o.getJSONObject("daily");
            d.sunrise = dy.getJSONArray("sunrise").getString(0).substring(11, 16);
            d.sunset = dy.getJSONArray("sunset").getString(0).substring(11, 16);
            d.uv = dy.getJSONArray("uv_index_max").optDouble(0, 0);
            d.hi = (int) Math.round(dy.getJSONArray("temperature_2m_max").getDouble(0));
            d.lo = (int) Math.round(dy.getJSONArray("temperature_2m_min").getDouble(0));
            d.rainMax = dy.getJSONArray("precipitation_probability_max").optInt(0, 0);
            d.code = dy.getJSONArray("weather_code").optInt(0, 0);
        } catch (Throwable ignored) {
        }
        try {
            JSONObject o = new JSONObject(get("https://marine-api.open-meteo.com/v1/marine"
                    + "?latitude=-33.8915&longitude=151.2767&hourly=wave_height,wave_direction"
                    + "&timezone=Australia%2FSydney&forecast_days=2"));
            JSONObject hr = o.getJSONObject("hourly");
            org.json.JSONArray t = hr.getJSONArray("time");
            long now = System.currentTimeMillis();
            d.dawnTomorrow = MainActivity.sydneyHour() >= 6;
            String day = MainActivity.fmt("yyyy-MM-dd", d.dawnTomorrow ? now + 86400000L : now);
            for (int i = 0; i < t.length(); i++) {
                if (t.getString(i).equals(day + "T06:00")) {
                    d.dawnWave = hr.getJSONArray("wave_height").getDouble(i);
                    d.dawnDir = (int) Math.round(hr.getJSONArray("wave_direction").optDouble(i, 90));
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return d;
    }

    static String describeCode(int code) {
        return describe(code);
    }

    static final class Surf {
        double wave, period, water = Double.NaN;
        int dir;
    }

    /** Bondi swell and water temperature from the Open-Meteo marine API (no key needed). */
    static Surf surf() {
        try {
            JSONObject o = new JSONObject(get("https://marine-api.open-meteo.com/v1/marine"
                    + "?latitude=-33.8915&longitude=151.2767"
                    + "&current=wave_height,wave_period,wave_direction,sea_surface_temperature"
                    + "&timezone=Australia%2FSydney"));
            JSONObject c = o.getJSONObject("current");
            Surf s = new Surf();
            s.wave = c.getDouble("wave_height");
            s.period = c.optDouble("wave_period", 0);
            s.dir = (int) Math.round(c.optDouble("wave_direction", 90));
            s.water = c.optDouble("sea_surface_temperature", Double.NaN);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    static Weather weather() {
        Weather w = openMeteo();
        return w != null ? w : wttr();
    }

    /** Backup source if Open-Meteo can't be reached. */
    private static Weather wttr() {
        try {
            JSONObject o = new JSONObject(get("https://wttr.in/Bondi,Australia?format=j1"));
            JSONObject cur = o.getJSONArray("current_condition").getJSONObject(0);
            JSONObject day = o.getJSONArray("weather").getJSONObject(0);
            Weather w = new Weather();
            w.temp = Integer.parseInt(cur.getString("temp_C"));
            w.hi = Integer.parseInt(day.getString("maxtempC"));
            w.lo = Integer.parseInt(day.getString("mintempC"));
            String d = cur.getJSONArray("weatherDesc").getJSONObject(0).getString("value").trim();
            String l = d.toLowerCase();
            w.code = l.contains("thunder") ? 95 : l.contains("heavy rain") ? 63
                    : (l.contains("rain") || l.contains("shower")) ? 61 : l.contains("drizzle") ? 51
                    : (l.contains("fog") || l.contains("mist")) ? 45
                    : (l.contains("overcast") || l.contains("cloudy") && !l.contains("partly")) ? 3
                    : l.contains("partly") ? 2 : 0;
            w.condition = d.isEmpty() ? describe(w.code) : Character.toUpperCase(d.charAt(0)) + d.substring(1).toLowerCase();
            w.wind = Integer.parseInt(cur.optString("windspeedKmph", "-1"));
            w.windDir = Integer.parseInt(cur.optString("winddirDegree", "0"));
            int h = MainActivity.sydneyHour();
            w.isDay = h >= 6 && h < 20;
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Weather openMeteo() {
        try {
            JSONObject o = new JSONObject(get(WEATHER_URL));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            Weather w = new Weather();
            w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            w.code = cur.getInt("weather_code");
            w.condition = describe(w.code);
            w.isDay = cur.optInt("is_day", 1) == 1;
            if (cur.has("wind_speed_10m")) {
                w.wind = (int) Math.round(cur.getDouble("wind_speed_10m"));
                w.windDir = (int) Math.round(cur.optDouble("wind_direction_10m", 0));
            }
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
        c.setRequestProperty("User-Agent", "Shelf/1.0 (Android TV; github.com/Jthhompson18/shelf)");
        c.setRequestProperty("Accept", "*/*");
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
