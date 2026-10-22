package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Place search for the weather card's location chooser, from Open-Meteo's free geocoding API (no key): the
 * request URL and the parsing of its JSON answer into "Sofia, Sofia-Capital, Bulgaria" style choices.
 */
final class Places {

    static final class Place {
        final String name;
        final String region;
        final String country;
        final double latitude;
        final double longitude;

        Place(String name, String region, String country, double latitude, double longitude) {
            this.name = name;
            this.region = region;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        /** "Sofia, Sofia-Capital, Bulgaria"; the region is left out when it repeats the name or is missing. */
        String label() {
            StringBuilder b = new StringBuilder(name);
            if (!region.isEmpty() && !region.equalsIgnoreCase(name)) b.append(", ").append(region);
            if (!country.isEmpty()) b.append(", ").append(country);
            return b.toString();
        }
    }

    private Places() {
    }

    static String url(String query, String language) {
        String name;
        try {
            name = URLEncoder.encode(query.trim(), "UTF-8");   // the Charset overload needs Android 13
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        String lang = language == null || language.isEmpty() ? "en" : language.toLowerCase(Locale.ROOT);
        return "https://geocoding-api.open-meteo.com/v1/search?name=" + name + "&count=8&language=" + lang
                + "&format=json";
    }

    /** The places in Open-Meteo's answer, best match first; empty when nothing matched. */
    static List<Place> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optBoolean("error", false)) throw new JSONException(root.optString("reason", "error"));
        JSONArray results = root.optJSONArray("results");
        if (results == null) return Collections.emptyList();
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !r.has("latitude") || !r.has("longitude")) continue;
            String name = r.optString("name", "").trim();
            if (name.isEmpty()) continue;
            out.add(new Place(name, r.optString("admin1", "").trim(), r.optString("country", "").trim(),
                    r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return out;
    }
}
