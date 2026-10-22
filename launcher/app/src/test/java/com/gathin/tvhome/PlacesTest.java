package com.gathin.tvhome;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the weather location search (Open-Meteo geocoding). */
public class PlacesTest {

    // Trimmed from a real answer for "Sofia" (2026-10-21).
    private static final String SOFIA = "{\"results\":["
            + "{\"id\":727011,\"name\":\"Sofia\",\"latitude\":42.69751,\"longitude\":23.32415,\"country\":\"Bulgaria\","
            + "\"admin1\":\"Sofia-Capital\",\"admin2\":\"Sofia\"},"
            + "{\"id\":617378,\"name\":\"Sofia\",\"latitude\":47.94476,\"longitude\":27.86484,\"country\":\"Moldova\","
            + "\"admin1\":\"Drochia\"},"
            + "{\"id\":1,\"name\":\"Monaco\",\"latitude\":43.73,\"longitude\":7.42,\"country\":\"Monaco\",\"admin1\":\"Monaco\"},"
            + "{\"id\":2,\"name\":\"Nowhere\",\"country\":\"Atlantis\"}"
            + "],\"generationtime_ms\":0.2}";

    @Test
    public void parsesPlacesInOrderAndSkipsOnesWithoutCoordinates() throws JSONException {
        List<Places.Place> places = Places.parse(SOFIA);
        assertEquals(3, places.size());
        Places.Place sofia = places.get(0);
        assertEquals("Sofia", sofia.name);
        assertEquals(42.69751, sofia.latitude, 1e-9);
        assertEquals(23.32415, sofia.longitude, 1e-9);
        assertEquals("Sofia, Sofia-Capital, Bulgaria", sofia.label());
        assertEquals("Sofia, Drochia, Moldova", places.get(1).label());
    }

    @Test
    public void aRegionThatRepeatsTheNameIsLeftOut() throws JSONException {
        assertEquals("Monaco, Monaco", Places.parse(SOFIA).get(2).label());
    }

    @Test
    public void noMatchesIsAnEmptyList() throws JSONException {
        assertTrue(Places.parse("{\"generationtime_ms\":0.1}").isEmpty());
    }

    @Test(expected = JSONException.class)
    public void anErrorAnswerIsRejected() throws JSONException {
        Places.parse("{\"error\":true,\"reason\":\"Parameter count must be between 1 and 100.\"}");
    }

    @Test
    public void urlEncodesTheQueryAndAsksInTheTvsLanguage() {
        String url = Places.url(" São Paulo ", "PT");
        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&"));
        assertTrue(url.contains("&language=pt"));
        assertTrue(url.endsWith("&format=json"));
        assertTrue(Places.url("Sofia", null).contains("&language=en"));
    }
}
