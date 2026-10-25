package tv.cleanhome.gtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recently added on the owner's own Plex Media Server. Sign-in is Plex's PIN link (plex.tv/link), the server
 * is found through the account's resources list. Items open on their watch.plex.tv page in the Plex app, which
 * shows Watch Now for titles on the owner's server; the current Plex app (2026.20) ignores plex://server:// links
 * and only opens its home screen. All network calls run off the main thread.
 */
final class Plex {
    static final String PLEX_APP = "com.plexapp.android";
    // Plezy (third-party Plex client) plays a library item from a link; the Plex app itself has no such link.
    static final String PLEZY = "com.edde746.plezy";

    final boolean plezy;

    final SharedPreferences prefs;

    Plex(Context c) {
        prefs = c.getSharedPreferences("plex", Context.MODE_PRIVATE);
        boolean has;
        try {
            has = c.getPackageManager().getApplicationInfo(PLEZY, 0).enabled;
        } catch (Exception e) {
            has = false;
        }
        plezy = has;
        if (prefs.getString("client", null) == null) {
            prefs.edit().putString("client", UUID.randomUUID().toString()).apply();
        }
    }

    boolean linked() {
        return prefs.getString("token", null) != null;
    }

    void unlink() {
        prefs.edit().remove("token").remove("uri").remove("machine").remove("serverToken").apply();
    }

    // ---- sign-in: plex.tv/link ----

    /** Starts a link: returns {id, code}; the owner types the code at plex.tv/link. */
    String[] newPin() throws Exception {
        JSONObject pin = new JSONObject(call("POST", "https://plex.tv/api/v2/pins?strong=false", null));
        return new String[]{pin.getString("id"), pin.getString("code")};
    }

    /** True once the owner entered the code; the account token is then saved. */
    boolean pinDone(String id) throws Exception {
        String token = new JSONObject(call("GET", "https://plex.tv/api/v2/pins/" + id, null)).optString("authToken", "");
        if (token.isEmpty() || token.equals("null")) return false;
        prefs.edit().putString("token", token).apply();
        return true;
    }

    // ---- server ----

    /** Finds the owner's server and the first connection that answers, preferring local and https. */
    boolean findServer() throws Exception {
        JSONArray resources = new JSONArray(call("GET",
                "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", prefs.getString("token", null)));
        for (int pass = 0; pass < 2; pass++) { // owned servers first, then shared ones
            for (int i = 0; i < resources.length(); i++) {
                JSONObject r = resources.getJSONObject(i);
                if (!r.optString("provides").contains("server") || r.optBoolean("owned") != (pass == 0)) continue;
                String token = r.optString("accessToken", prefs.getString("token", null));
                List<String> uris = new ArrayList<>();
                JSONArray cons = r.optJSONArray("connections");
                for (int local = 1; local >= 0 && cons != null; local--) {
                    for (int j = 0; j < cons.length(); j++) {
                        JSONObject c = cons.getJSONObject(j);
                        if (c.optBoolean("local") == (local == 1) && "https".equals(c.optString("protocol"))) {
                            uris.add(c.getString("uri"));
                        }
                    }
                }
                for (String uri : uris) {
                    try {
                        call("GET", uri + "/identity", token);
                        prefs.edit().putString("uri", uri).putString("serverToken", token)
                                .putString("machine", r.getString("clientIdentifier")).apply();
                        return true;
                    } catch (Exception unreachable) {
                        // try the next connection
                    }
                }
            }
        }
        return false;
    }

    // ---- recently added ----

    /** Movies and TV from the server's own libraries, newest first; one card per show. */
    List<MainActivity.Item> recentlyAdded(int limit) {
        List<MainActivity.Item> out = new ArrayList<>();
        if (!linked()) return out;
        try {
            if (prefs.getString("uri", null) == null && !findServer()) return out;
            JSONObject page;
            try {
                page = fetchRecent();
            } catch (HttpError e) {
                if (e.code != 401 || !findServer()) throw e; // the server token can rotate: look it up again
                page = fetchRecent();
            }
            JSONArray items = page.getJSONObject("MediaContainer").optJSONArray("Metadata");
            Set<String> shows = new HashSet<>();
            for (int i = 0; items != null && i < items.length() && out.size() < limit; i++) {
                JSONObject m = items.getJSONObject(i);
                String type = m.optString("type");
                MainActivity.Item it = new MainActivity.Item();
                it.pkg = PLEX_APP;
                String art;
                if (type.equals("movie")) {
                    it.title = m.optString("title");
                    it.sub = "Plex · " + section(m) + (m.has("year") ? " · " + m.optInt("year") : "");
                    art = first(m, "art", "thumb");
                } else if (type.equals("season") || type.equals("episode") || type.equals("show")) {
                    String show = type.equals("show") ? m.optString("title")
                            : type.equals("season") ? m.optString("parentTitle") : m.optString("grandparentTitle");
                    if (!shows.add(show)) continue;
                    it.title = show;
                    it.sub = "Plex · " + (type.equals("season") ? m.optString("title")
                            : type.equals("episode") ? "S" + m.optInt("parentIndex") + " E" + m.optInt("index") + " · " + m.optString("title")
                            : section(m));
                    art = type.equals("episode") ? first(m, "thumb", "grandparentArt") : first(m, "art", "parentArt", "thumb");
                } else {
                    continue; // music, photos, clips: not part of this row
                }
                it.image = image(art);
                // Shows open on the show's page: seasons and episodes carry the show's guid as parent/grandparent.
                String guid = type.equals("movie") || type.equals("show") ? m.optString("guid")
                        : type.equals("season") ? m.optString("parentGuid") : m.optString("grandparentGuid");
                String playable = type.equals("movie") || type.equals("episode") ? m.optString("ratingKey")
                        : nextEpisode(m.optString("ratingKey"), type);
                if (plezy && !TextUtils.isEmpty(playable)) {
                    it.pkg = PLEZY;
                    it.intentUri = "plezy://play?content_id=plezy_" + prefs.getString("machine", "") + "_" + playable;
                    out.add(it);
                    continue;
                }
                String watch = watchPage(guid);
                it.intentUri = watch != null ? watch : "plex://server://" + prefs.getString("machine", "")
                        + "/com.plexapp.plugins.library/library/metadata/" + m.optString("ratingKey");
                out.add(it);
            }
        } catch (HttpError e) {
            if (e.code == 401) unlink(); // the account token was revoked: show the link card again
        } catch (Exception ignored) {
            // server asleep or offline: leave the row out until next time
        }
        return out;
    }

    /** The first unwatched episode of a season or show (else its first episode), as a ratingKey; null on failure. */
    String nextEpisode(String key, String type) {
        try {
            String path = type.equals("season") ? "/children" : "/allLeaves";
            JSONArray eps = new JSONObject(call("GET", prefs.getString("uri", "") + "/library/metadata/" + key + path,
                    prefs.getString("serverToken", null))).getJSONObject("MediaContainer").optJSONArray("Metadata");
            if (eps == null || eps.length() == 0) return null;
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                if (e.optInt("viewCount", 0) == 0) return e.optString("ratingKey");
            }
            return eps.getJSONObject(0).optString("ratingKey");
        } catch (Exception e) {
            return null;
        }
    }

    /** https://watch.plex.tv/movie|show/<slug> for a plex://movie|show/<id> guid; slugs are cached. Null if unmatched. */
    String watchPage(String guid) {
        if (TextUtils.isEmpty(guid) || !guid.startsWith("plex://")) return null; // local-only or old-agent match
        String cached = prefs.getString("slug:" + guid, null);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String[] parts = guid.substring("plex://".length()).split("/"); // {movie|show, id}
        if (parts.length != 2) return null;
        try {
            JSONObject m = new JSONObject(call("GET", "https://discover.provider.plex.tv/library/metadata/" + parts[1],
                    prefs.getString("token", null))).getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0);
            String slug = m.optString("slug");
            String page = slug.isEmpty() ? "" : "https://watch.plex.tv/" + parts[0] + "/" + slug;
            prefs.edit().putString("slug:" + guid, page).apply();
            return page.isEmpty() ? null : page;
        } catch (Exception e) {
            return null; // offline: try again next refresh
        }
    }

    JSONObject fetchRecent() throws Exception {
        return new JSONObject(call("GET", prefs.getString("uri", "")
                + "/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=60",
                prefs.getString("serverToken", null)));
    }

    String image(String key) {
        if (TextUtils.isEmpty(key)) return null;
        return prefs.getString("uri", "") + "/photo/:/transcode?width=480&height=270&minSize=1&upscale=1&url="
                + Uri.encode(key) + "&X-Plex-Token=" + Uri.encode(prefs.getString("serverToken", ""));
    }

    static String section(JSONObject m) {
        return m.optString("librarySectionTitle", m.optString("type"));
    }

    static String first(JSONObject m, String... keys) {
        for (String k : keys) if (!m.optString(k).isEmpty()) return m.optString(k);
        return null;
    }

    // ---- http ----

    static final class HttpError extends Exception {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    String call(String method, String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Product", "Home launcher");
        c.setRequestProperty("X-Plex-Client-Identifier", prefs.getString("client", ""));
        c.setRequestProperty("X-Plex-Device-Name", "TV Home launcher");
        if (token != null) c.setRequestProperty("X-Plex-Token", token);
        if (method.equals("POST")) c.setFixedLengthStreamingMode(0);
        int code = c.getResponseCode();
        if (code >= 400) throw new HttpError(code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
