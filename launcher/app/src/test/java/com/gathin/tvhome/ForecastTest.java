package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the Open-Meteo CSV forecast behind the home-screen weather card. */
public class ForecastTest {

    // A real Sofia response (2026-10-21), as Open-Meteo sends it with format=csv.
    private static final String SOFIA =
            "latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation\n"
            + "42.731186,23.344542,548.0,10800,Europe/Sofia,GMT+3\n"
            + "\n"
            + "time,temperature_2m (°C),weather_code (wmo code)\n"
            + "2026-10-21T22:00,13.2,3\n"
            + "\n"
            + "time,weather_code (wmo code),temperature_2m_max (°C),temperature_2m_min (°C),precipitation_probability_max (%)\n"
            + "2026-10-21,45,21.1,5.3,5\n"
            + "2026-10-22,95,20.8,10.9,75\n"
            + "2026-10-23,95,15.4,5.8,98\n";

    @Test
    public void parsesCurrentConditionsAndEveryDay() {
        Forecast f = Forecast.parseCsv(SOFIA);
        assertEquals(13.2, f.currentTemp, 1e-9);
        assertEquals(3, f.currentCode);
        assertEquals(3, f.days.size());
        Forecast.Day wed = f.days.get(1);
        assertEquals(LocalDate.of(2026, 10, 22), wed.date);
        assertEquals(95, wed.code);
        assertEquals(20.8, wed.max, 1e-9);
        assertEquals(10.9, wed.min, 1e-9);
        assertEquals(75, wed.rain);
    }

    @Test
    public void windowsLineEndingsAndAMissingRainValueStillParse() {
        String csv = SOFIA.replace("\n", "\r\n").replace("2026-10-23,95,15.4,5.8,98", "2026-10-23,95,15.4,5.8,");
        Forecast f = Forecast.parseCsv(csv);
        assertEquals(3, f.days.size());
        assertEquals(0, f.days.get(2).rain);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anErrorPageIsRejected() {
        Forecast.parseCsv("{\"error\":true,\"reason\":\"Cannot initialize WeatherVariable\"}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aResponseWithoutDaysIsRejected() {
        Forecast.parseCsv(SOFIA.substring(0, SOFIA.indexOf("time,weather_code")));
    }

    @Test
    public void daysBeforeTodayAreSkippedAfterMidnight() {
        List<Forecast.Day> days = Forecast.parseCsv(SOFIA).from(LocalDate.of(2026, 10, 22), 3);
        assertEquals(2, days.size());
        assertEquals(LocalDate.of(2026, 10, 22), days.get(0).date);
    }

    @Test
    public void barsShareOneScaleFromTheLowestLowToTheHighestHigh() {
        double[] scale = Forecast.scale(Forecast.parseCsv(SOFIA).days);
        assertEquals(5.3, scale[0], 1e-9);
        assertEquals(21.1, scale[1], 1e-9);
        assertEquals(0f, Forecast.position(5.3, scale), 1e-6);
        assertEquals(1f, Forecast.position(30, scale), 1e-6);   // clamped
        assertEquals(0.5f, Forecast.position(13.2, scale), 1e-6);
    }

    @Test
    public void colourGoesFromBlueWhenColdToRedWhenHot() {
        int cold = Forecast.color(-20), mild = Forecast.color(16), hot = Forecast.color(40);
        assertTrue((cold & 0xFF) > ((cold >> 16) & 0xFF));            // more blue than red
        assertTrue(((hot >> 16) & 0xFF) > (hot & 0xFF));              // more red than blue
        assertEquals(0xFFA8D38F, mild);                               // exactly on a stop
    }

    @Test
    public void everyWeatherCodeHasAnIcon() {
        for (int code : new int[]{0, 1, 2, 3, 45, 51, 61, 71, 80, 95, 99, 42}) {
            assertTrue(!Forecast.icon(code).isEmpty());
        }
        assertEquals(Forecast.icon(61), Forecast.icon(81));   // rain and rain showers look alike
        assertEquals("💧", Forecast.chanceIcon(61));   // rain: a drop
        assertEquals("💧", Forecast.chanceIcon(53));   // drizzle: a drop too
        assertEquals("❄️", Forecast.chanceIcon(73));   // snow: a snowflake
        assertEquals("❄️", Forecast.chanceIcon(86));   // snow showers
        assertTrue(!Forecast.snow(95) && !Forecast.snow(66));   // storms and freezing rain keep the drop
    }

    @Test
    public void urlAsksForCsvInThePlacesOwnTimezone() {
        String url = Forecast.url(42.6977, 23.3219, 3);
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=42.6977&longitude=23.3219"));
        assertTrue(url.contains("&timezone=auto"));
        assertTrue(url.contains("&forecast_days=3"));
        assertTrue(url.endsWith("&format=csv"));
    }
}
