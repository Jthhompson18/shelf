package com.jack.shelf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;

/**
 * Tiny one-page web server on the home Wi-Fi so the calendar link can be pasted from a phone
 * instead of typed with the remote. Runs only while the setup box is open.
 */
final class LinkSetup {

    interface Listener {
        void onLink(String url);
    }

    static final int PORT = 8321;
    private ServerSocket server;
    private volatile boolean running;

    String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    void start(final Listener l) {
        stop();
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (running) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(8000);
                        handle(s, l);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private void handle(Socket s, Listener l) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String first = in.readLine();
        if (first == null) return;
        int len = 0;
        String h;
        while ((h = in.readLine()) != null && !h.isEmpty()) {
            if (h.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
        }
        String page;
        if (first.startsWith("POST")) {
            char[] body = new char[Math.min(len, 8192)];
            int got = 0;
            while (got < body.length) {
                int r = in.read(body, got, body.length - got);
                if (r < 0) break;
                got += r;
            }
            String form = new String(body, 0, got);
            String url = "";
            for (String kv : form.split("&")) {
                if (kv.startsWith("url=")) url = URLDecoder.decode(kv.substring(4), "UTF-8").trim();
            }
            if (url.startsWith("http") || url.startsWith("webcal")) {
                l.onLink(url);
                page = done();
            } else {
                page = form("That doesn't look like a calendar link — it should start with https:// or webcal://");
            }
        } else {
            page = form(null);
        }
        byte[] b = page.getBytes("UTF-8");
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private static String shell(String inner) {
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Shelf</title><style>body{font:16px -apple-system,system-ui,sans-serif;background:#0d0f14;"
                + "color:#f2f2f2;margin:0;padding:28px}h1{font-weight:600;font-size:26px;margin:0 0 6px}"
                + "p,li{color:#b9bcc6;line-height:1.5}textarea{width:100%;box-sizing:border-box;height:110px;"
                + "border-radius:14px;border:1px solid #333;background:#171a21;color:#fff;padding:12px;font-size:15px}"
                + "button{margin-top:14px;width:100%;padding:15px;border:0;border-radius:14px;background:#fff;"
                + "color:#000;font-size:17px;font-weight:600}.err{color:#ff8a80}details{margin-top:22px}"
                + "summary{color:#fff;font-weight:600}</style></head><body>" + inner + "</body></html>";
    }

    private static String form(String err) {
        return shell("<h1>Add your calendar</h1><p>Paste your calendar's private link below. "
                + "Shelf shows today's and tomorrow's events on the projector.</p>"
                + (err != null ? "<p class=err>" + err + "</p>" : "")
                + "<form method=post><textarea name=url placeholder='https://… or webcal://…'></textarea>"
                + "<button>Save to Shelf</button></form>"
                + "<details><summary>iPhone / iCloud calendar</summary><ol>"
                + "<li>Open the Calendar app and tap <b>Calendars</b>.</li>"
                + "<li>Tap the <b>(i)</b> next to your calendar and switch on <b>Public Calendar</b>.</li>"
                + "<li>Tap <b>Share Link</b> → <b>Copy</b>, then paste it here.</li></ol>"
                + "<p>Anyone with that link could see the calendar, so don't share it elsewhere.</p></details>"
                + "<details><summary>Google Calendar</summary><ol>"
                + "<li>On a computer, open calendar.google.com → Settings.</li>"
                + "<li>Pick your calendar → <b>Integrate calendar</b>.</li>"
                + "<li>Copy the <b>Secret address in iCal format</b> and paste it here.</li></ol></details>");
    }

    private static String done() {
        return shell("<h1>Saved ✓</h1><p>Your calendar is now on Shelf. You can close this page.</p>");
    }
}
