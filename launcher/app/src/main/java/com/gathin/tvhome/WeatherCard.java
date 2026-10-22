package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The home-screen weather card, drawn in one view: a glass panel with the place and the current temperature on
 * top, then a row per day as in Apple's Weather app (day, icon with the chance of rain under it, low, range bar,
 * high). The bars share one scale across the days and are coloured cool to warm; today's has a dot at the
 * current temperature. Nothing animates and nothing is blurred, so it costs one draw when the forecast changes.
 */
final class WeatherCard extends View {

    /** Rain chance under the icon only from this much on, as Apple's list leaves dry days clean. */
    private static final int RAIN_SHOWN_FROM = 20;

    /** The card's unit: the density, or 85 % of it when compact. */
    private float dp;
    private final float density;
    private int dayCount = 4;
    /** Glass: a light white tint fading downwards, as the dock's glass, instead of the dark panel. */
    private boolean glass;
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, medium;
    private final RectF rect = new RectF();

    private String place = "";
    private Forecast forecast;
    private List<Forecast.Day> days;
    /** "Updated 09:40" when the forecast is old, otherwise null. */
    private String stale;

    WeatherCard(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        dp = density;
        regular = getResources().getFont(R.font.outfit_regular);
        medium = getResources().getFont(R.font.outfit_medium);
        panel.setColor(0x8C1C202C);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, dp));
        border.setColor(0x2EFFFFFF);
        line.setColor(0x1FFFFFFF);
        line.setStrokeWidth(Math.max(1f, dp * 0.75f));
        track.setColor(0x47000000);
        dot.setColor(0xFFFFFFFF);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Launcher settings: Config.DOCK_SOLID, DOCK_TRANSLUCENT or DOCK_GLASS; compact; 1 to 4 days. */
    void setLook(String style, boolean compact, int count) {
        glass = Config.DOCK_GLASS.equals(style);
        panel.setShader(null);
        panel.setColor(Config.DOCK_SOLID.equals(style) ? 0xF21C202C : 0x8C1C202C);
        border.setColor(glass ? 0x30FFFFFF : 0x2EFFFFFF);
        dp = compact ? density * 0.85f : density;
        border.setStrokeWidth(Math.max(1f, density));
        dayCount = Math.max(1, Math.min(4, count));
        if (forecast != null) days = forecast.from(days == null || days.isEmpty() ? LocalDate.now() : days.get(0).date, dayCount);
        requestLayout();
        invalidate();
    }

    /** Shows this forecast; {@code stale} is the "Updated ..." note, or null when it is fresh. */
    void show(String place, Forecast forecast, LocalDate today, String stale) {
        this.place = place == null ? "" : place;
        this.forecast = forecast;
        this.days = forecast.from(today, dayCount);
        this.stale = stale;
        requestLayout();
        invalidate();
    }

    boolean hasForecast() {
        return days != null && !days.isEmpty();
    }

    private static final float PAD_V = 8, PAD_H = 16, HEAD = 22, ROW = 30, RADIUS = 16;

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rows = days == null ? 0 : days.size();
        int h = Math.round((2 * PAD_V + HEAD + rows * ROW) * dp);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!hasForecast()) return;
        float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        if (glass) {
            // The dock's glass: white, 10% at the top fading to 3%, with a 19% edge.
            panel.setShader(new LinearGradient(0, 0, 0, h, 0x1AFFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(rect, RADIUS * dp, RADIUS * dp, panel);
        rect.inset(border.getStrokeWidth() / 2, border.getStrokeWidth() / 2);
        c.drawRoundRect(rect, RADIUS * dp, RADIUS * dp, border);

        float left = PAD_H * dp, right = w - PAD_H * dp;

        // Header: SOFIA on the left, the current temperature and icon (or the "Updated" note) on the right.
        float headBase = (PAD_V + HEAD - 7) * dp;
        text.setTypeface(medium);
        text.setTextSize(11 * dp);
        text.setLetterSpacing(0.14f);
        text.setColor(0xBFFFFFFF);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(place.toUpperCase(Locale.getDefault()), left, headBase, text);
        text.setLetterSpacing(0f);
        if (stale != null) {
            text.setTypeface(regular);
            text.setTextSize(11 * dp);
            text.setColor(0x99FFFFFF);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(stale, right, headBase, text);
        } else {
            emoji.setTextSize(13 * dp);
            emoji.setTextAlign(Paint.Align.RIGHT);
            String icon = Forecast.icon(forecast.currentCode);
            c.drawText(icon, right, headBase, emoji);
            text.setTypeface(medium);
            text.setTextSize(14 * dp);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(degrees(forecast.currentTemp), right - emoji.measureText(icon) - 6 * dp, headBase, text);
        }
        float y = (PAD_V + HEAD) * dp;
        c.drawLine(left, y, right, y, line);

        // Columns, as fractions of the inner width: day, icon, low, bar, high.
        float inner = right - left;
        float iconX = left + inner * 0.27f;
        float lowRight = left + inner * 0.46f;
        float barL = left + inner * 0.50f, barR = left + inner * 0.84f;
        double[] scale = Forecast.scale(days);
        LocalDate today = days.get(0).date;

        for (int i = 0; i < days.size(); i++) {
            Forecast.Day d = days.get(i);
            float top = y + i * ROW * dp, mid = top + ROW * dp / 2;
            if (i > 0) c.drawLine(left, top, right, top, line);
            float base = mid + 5 * dp;

            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(medium);
            text.setTextSize(15 * dp);
            text.setColor(0xFFFFFFFF);
            c.drawText(dayName(d.date, today), left, base, text);

            boolean rain = d.rain >= RAIN_SHOWN_FROM;
            emoji.setTextAlign(Paint.Align.CENTER);
            emoji.setTextSize(15 * dp);
            c.drawText(Forecast.icon(d.code), iconX, rain ? mid + 1 * dp : base, emoji);
            if (rain) {
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(9 * dp);
                text.setColor(Forecast.snow(d.code) ? 0xFFE3EEF7 : 0xFF7CC4F2);
                c.drawText(Forecast.chanceIcon(d.code) + d.rain + "%", iconX, mid + 11 * dp, text);
            }

            text.setTextAlign(Paint.Align.RIGHT);
            text.setTypeface(regular);
            text.setTextSize(15 * dp);
            text.setColor(0x80FFFFFF);
            c.drawText(degrees(d.min), lowRight, base, text);
            text.setTypeface(medium);
            text.setColor(0xFFFFFFFF);
            c.drawText(degrees(d.max), right, base, text);

            float barH = 4 * dp;
            rect.set(barL, mid - barH / 2, barR, mid + barH / 2);
            c.drawRoundRect(rect, barH / 2, barH / 2, track);
            float from = barL + (barR - barL) * Forecast.position(d.min, scale);
            float to = barL + (barR - barL) * Forecast.position(d.max, scale);
            if (to - from < barH) to = Math.min(barR, from + barH);
            bar.setShader(new LinearGradient(from, 0, to, 0, Forecast.color(d.min), Forecast.color(d.max), Shader.TileMode.CLAMP));
            rect.set(from, mid - barH / 2, to, mid + barH / 2);
            c.drawRoundRect(rect, barH / 2, barH / 2, bar);

            if (i == 0 && d.date.equals(today) && stale == null) {
                float x = barL + (barR - barL) * Forecast.position(
                        Math.max(d.min, Math.min(d.max, forecast.currentTemp)), scale);
                dot.setColor(0xFF1C202C);
                c.drawCircle(x, mid, 4.5f * dp, dot);
                dot.setColor(0xFFFFFFFF);
                c.drawCircle(x, mid, 3f * dp, dot);
            }
        }
    }

    private String dayName(LocalDate date, LocalDate today) {
        if (date.equals(today)) return getResources().getString(R.string.weather_today);
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    private static String degrees(double t) {
        long r = Math.round(t);
        return (r == 0 ? 0 : r) + "°";   // never "-0°"
    }
}
