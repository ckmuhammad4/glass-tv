package com.gathin.tvhome;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The five daily prayer times, worked out on the TV from the sun's position, so no network is needed.
 * Defaults are Kuala Lumpur with JAKIM's method: Fajr at 20 degrees, Isha at 18, Asr by the Shafi'i
 * shadow rule, and JAKIM's small safety margins. Times can differ from the published ones by about a
 * minute.
 */
final class PrayerTimes {
    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};

    static final double LAT = 3.1390, LON = 101.6869;
    static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Kuala_Lumpur");
    private static final double FAJR_ANGLE = 20, ISHA_ANGLE = 18, SUN_ANGLE = 0.833;
    // Minutes added after the astronomical time, as JAKIM does.
    private static final double[] MARGIN_MIN = {0, 2, 1, 1, 1};

    private PrayerTimes() {}

    /** Epoch milliseconds of Fajr, Dhuhr, Asr, Maghrib and Isha on the given day (in {@link #ZONE}). */
    static long[] forDay(int year, int month, int day) {
        Calendar midnight = Calendar.getInstance(ZONE);
        midnight.clear();
        midnight.set(year, month - 1, day, 0, 0, 0);
        double tz = ZONE.getOffset(midnight.getTimeInMillis()) / 3600000.0;
        double jd = julian(year, month, day) - LON / (15 * 24);
        double[] h = new double[5];
        h[0] = angleTime(jd, 5 / 24.0, FAJR_ANGLE, true);
        h[1] = 12 - sun(jd + 12 / 24.0)[1];
        h[2] = asr(jd, 13 / 24.0);
        h[3] = angleTime(jd, 18 / 24.0, SUN_ANGLE, false);
        h[4] = angleTime(jd, 18 / 24.0, ISHA_ANGLE, false);
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            double hour = h[i] + tz - LON / 15 + MARGIN_MIN[i] / 60.0;
            out[i] = midnight.getTimeInMillis() + Math.round(hour * 3600000.0);
        }
        return out;
    }

    private static double angleTime(double jd, double t, double angle, boolean before) {
        double[] s = sun(jd + t);
        double noon = 12 - s[1];
        double a = (-sin(angle) - sin(s[0]) * sin(LAT)) / (cos(s[0]) * cos(LAT));
        double d = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a)))) / 15;
        return before ? noon - d : noon + d;
    }

    private static double asr(double jd, double t) {
        double[] s = sun(jd + t);
        double noon = 12 - s[1];
        double altitude = Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(LAT - s[0]))))));
        double a = (sin(altitude) - sin(LAT) * sin(s[0])) / (cos(LAT) * cos(s[0]));
        return noon + Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a)))) / 15;
    }

    /** Declination (degrees) and equation of time (hours) at a Julian date. */
    private static double[] sun(double jd) {
        double d = jd - 2451544.5;
        double g = fix(357.529 + 0.98560028 * d, 360);
        double q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = fix(Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15, 24);
        double eqt = q / 15 - ra;
        if (eqt > 12) eqt -= 24;
        if (eqt < -12) eqt += 24;
        return new double[]{Math.toDegrees(Math.asin(sin(e) * sin(l))), eqt};
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    private static double fix(double v, double n) { v = v - n * Math.floor(v / n); return v < 0 ? v + n : v; }
    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }

}
