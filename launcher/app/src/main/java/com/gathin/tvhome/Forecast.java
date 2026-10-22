package com.gathin.tvhome;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A few days of weather for one place, as Open-Meteo returns it in CSV (no key, no account). Plain Java so it
 * can be unit-tested: parsing, the request URL, the icon for a WMO weather code, and the shared temperature
 * scale and colours the card draws its range bars with.
 *
 * The CSV has three blocks separated by blank lines: the location, the current conditions and one line per day.
 */
final class Forecast {

    static final class Day {
        final LocalDate date;
        final int code;
        final double max;
        final double min;
        /** Highest chance of precipitation that day, 0 to 100. */
        final int rain;

        Day(LocalDate date, int code, double max, double min, int rain) {
            this.date = date;
            this.code = code;
            this.max = max;
            this.min = min;
            this.rain = rain;
        }
    }

    final double currentTemp;
    final int currentCode;
    final List<Day> days;

    Forecast(double currentTemp, int currentCode, List<Day> days) {
        this.currentTemp = currentTemp;
        this.currentCode = currentCode;
        this.days = Collections.unmodifiableList(days);
    }

    static String url(double latitude, double longitude, int days) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=%d&format=csv", latitude, longitude, days);
    }

    /** Parses Open-Meteo's CSV; throws IllegalArgumentException when a block or column is missing. */
    static Forecast parseCsv(String csv) {
        if (csv == null) throw new IllegalArgumentException("no data");
        String[] blocks = csv.replace("\r", "").trim().split("\n\\s*\n");
        double temp = Double.NaN;
        int code = -1;
        List<Day> days = new ArrayList<>();
        for (String block : blocks) {
            String[] lines = block.trim().split("\n");
            if (lines.length < 2) continue;
            String[] head = lines[0].split(",");
            int time = column(head, "time");
            if (time < 0) continue;
            int max = column(head, "temperature_2m_max");
            if (max >= 0) {
                int min = column(head, "temperature_2m_min");
                int wmo = column(head, "weather_code");
                int rain = column(head, "precipitation_probability_max");
                if (min < 0 || wmo < 0) throw new IllegalArgumentException("daily columns missing");
                for (int i = 1; i < lines.length; i++) {
                    String[] f = lines[i].split(",", -1);
                    if (f.length < head.length) continue;
                    days.add(new Day(LocalDate.parse(f[time].trim()), (int) number(f[wmo]),
                            number(f[max]), number(f[min]), rain < 0 || f[rain].trim().isEmpty() ? 0 : (int) Math.round(number(f[rain]))));
                }
            } else {
                int t = column(head, "temperature_2m");
                int wmo = column(head, "weather_code");
                if (t >= 0 && wmo >= 0) {
                    String[] f = lines[1].split(",", -1);
                    temp = number(f[t]);
                    code = (int) number(f[wmo]);
                }
            }
        }
        if (days.isEmpty() || Double.isNaN(temp)) throw new IllegalArgumentException("forecast incomplete");
        return new Forecast(temp, code, days);
    }

    /** The column whose header starts with this name ("temperature_2m (°C)" matches "temperature_2m"). */
    private static int column(String[] head, String name) {
        for (int i = 0; i < head.length; i++) {
            String h = head[i].trim();
            if (h.equals(name) || h.startsWith(name + " ")) return i;
        }
        return -1;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + s);
        }
    }

    /** The days from {@code today} on, at most {@code count}: yesterday's line is dropped after midnight. */
    List<Day> from(LocalDate today, int count) {
        List<Day> out = new ArrayList<>();
        for (Day d : days) {
            if (!d.date.isBefore(today) && out.size() < count) out.add(d);
        }
        return out;
    }

    /** Emoji for a WMO weather code (Open-Meteo's weather_code). */
    static String icon(int code) {
        if (code == 0) return "☀️";                    // clear
        if (code == 1) return "🌤️";              // mainly clear
        if (code == 2) return "⛅";                          // partly cloudy
        if (code == 3) return "☁️";                    // overcast
        if (code == 45 || code == 48) return "☁️";   // fog: Android's fog emoji draws as a grey box
        if (code >= 51 && code <= 57) return "🌦️"; // drizzle
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️"; // rain
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "🌨️";    // snow
        if (code >= 95) return "⛈️";                   // thunderstorm
        return "☁️";
    }

    /** Whether a WMO code is snow (snow fall, snow grains, snow showers): its chance reads as snow, not rain. */
    static boolean snow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    /** The mark before a day's chance of precipitation: a snowflake on snow days, a drop on every other. */
    static String chanceIcon(int code) {
        return snow(code) ? "❄️" : "💧";
    }

    /** Lowest low and highest high over these days: the scale every range bar shares. */
    static double[] scale(List<Day> days) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Day d : days) {
            lo = Math.min(lo, d.min);
            hi = Math.max(hi, d.max);
        }
        if (hi - lo < 1) hi = lo + 1;
        return new double[]{lo, hi};
    }

    /** Where a temperature sits on the shared scale, 0 to 1. */
    static float position(double temp, double[] scale) {
        return (float) Math.max(0, Math.min(1, (temp - scale[0]) / (scale[1] - scale[0])));
    }

    // Cool to warm, as Apple's weather bars: blue below freezing, through teal and green, to yellow and red.
    private static final double[] STOP_TEMPS = {-10, 0, 10, 16, 22, 28, 35};
    private static final int[] STOP_COLORS = {0xFF5E8BFF, 0xFF5AC8FA, 0xFF7FD3E8, 0xFFA8D38F, 0xFFF2D24A, 0xFFF5A623, 0xFFFF6B4A};

    /** Bar colour for a temperature in °C, interpolated between the stops. */
    static int color(double temp) {
        if (temp <= STOP_TEMPS[0]) return STOP_COLORS[0];
        for (int i = 1; i < STOP_TEMPS.length; i++) {
            if (temp <= STOP_TEMPS[i]) {
                float t = (float) ((temp - STOP_TEMPS[i - 1]) / (STOP_TEMPS[i] - STOP_TEMPS[i - 1]));
                return blend(STOP_COLORS[i - 1], STOP_COLORS[i], t);
            }
        }
        return STOP_COLORS[STOP_COLORS.length - 1];
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
