package com.power.usefulcomponents.uv;

import net.minecraft.network.chat.Component;

/**
 * Turns a duration in ticks (20 per second, as in the recipe json) into the most readable unit:
 * ticks, seconds, minutes + seconds, or hours + minutes.
 */
public final class UvTimeFormat {
    private UvTimeFormat() {
    }

    /** "15 ticks", "5 s", "2.5 s", "1 min 30 s", "1 h 5 min" - without the raw tick count. */
    public static Component readable(int ticks) {
        if (ticks < 20)
            return Component.translatable("usefulcomponents.time.ticks", ticks);

        if (ticks < 1200) { // under a minute
            float seconds = ticks / 20f;
            String text = ticks % 20 == 0 ? Integer.toString(ticks / 20) : String.format("%.1f", seconds);
            return Component.translatable("usefulcomponents.time.seconds", text);
        }

        if (ticks < 72000) { // under an hour
            int minutes = ticks / 1200;
            int seconds = Math.round((ticks % 1200) / 20f);
            if (seconds == 60) {
                minutes++;
                seconds = 0;
            }
            return seconds == 0
                    ? Component.translatable("usefulcomponents.time.minutes", minutes)
                    : Component.translatable("usefulcomponents.time.minutes_seconds", minutes, seconds);
        }

        int hours = ticks / 72000;
        int minutes = Math.round((ticks % 72000) / 1200f);
        if (minutes == 60) {
            hours++;
            minutes = 0;
        }
        return minutes == 0
                ? Component.translatable("usefulcomponents.time.hours", hours)
                : Component.translatable("usefulcomponents.time.hours_minutes", hours, minutes);
    }

    /** Readable time with the exact tick count in brackets, e.g. "5 s (100 ticks)". */
    public static Component withTicks(int ticks) {
        if (ticks < 20)
            return readable(ticks);
        return Component.translatable("usefulcomponents.time.with_ticks", readable(ticks), ticks);
    }
}
