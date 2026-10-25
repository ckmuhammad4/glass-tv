package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;

import org.junit.Test;

public class PrayerTimesTest {

    private static int minuteOfDay(long millis) {
        Calendar c = Calendar.getInstance(PrayerTimes.ZONE);
        c.setTimeInMillis(millis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    @Test
    public void kualaLumpurTimesAreInOrderAndPlausible() {
        for (int month = 1; month <= 12; month++) {
            long[] t = PrayerTimes.forDay(2026, month, 15);
            assertEquals(5, t.length);
            for (int i = 1; i < 5; i++) assertTrue("month " + month, t[i] > t[i - 1]);
            assertTrue(minuteOfDay(t[0]) >= 5 * 60 + 20 && minuteOfDay(t[0]) <= 6 * 60 + 15);   // Fajr
            assertTrue(minuteOfDay(t[1]) >= 12 * 60 + 50 && minuteOfDay(t[1]) <= 13 * 60 + 30);  // Dhuhr
            assertTrue(minuteOfDay(t[2]) >= 16 * 60 && minuteOfDay(t[2]) <= 17 * 60);            // Asr
            assertTrue(minuteOfDay(t[3]) >= 18 * 60 + 40 && minuteOfDay(t[3]) <= 19 * 60 + 35); // Maghrib
            assertTrue(minuteOfDay(t[4]) >= 19 * 60 + 50 && minuteOfDay(t[4]) <= 20 * 60 + 50); // Isha
        }
    }
}
