package com.power.usefulcomponents.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration for Useful Components.
 *
 * Built on {@link ModConfigSpec} so it is automatically readable/editable by
 * the "Configured" mod. All entries include a comment(...) call, which
 * "Configured" surfaces as a tooltip, and translatable-friendly keys via
 * translation(...).
 *
 * Generates: config/usefulcomponents-common.toml at runtime (NeoForge
 * always persists ModConfigSpec-backed configs as .toml, regardless of the
 * requested file name passed to registerConfig).
 */
public final class UsefulComponentsConfig {

    public static final ModConfigSpec SPEC;

    // --- Voltage Regulator ---
    public static final ModConfigSpec.DoubleValue REGULATOR_EFFICIENCY;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_TEMP;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_INPUT_VOLTAGE;

    // --- Signal Oscillator ---
    public static final ModConfigSpec.IntValue OSCILLATOR_MAX_FREQUENCY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Adjustable Voltage Regulator settings")
                .push("voltageRegulator");

        REGULATOR_EFFICIENCY = builder
                .comment("Power efficiency of the voltage regulator (0.0 - 1.0). ",
                        "The remaining fraction of (VIN * drawn current) is turned into heat.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorEfficiency")
                .defineInRange("regulatorEfficiency", 0.8D, 0.0D, 1.0D);

        REGULATOR_MAX_TEMP = builder
                .comment("Maximum internal temperature (Celsius) before the regulator burns out.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxTemp")
                .defineInRange("regulatorMaxTemp", 100.0D, 0.0D, 300.0D);

        REGULATOR_MAX_INPUT_VOLTAGE = builder
                .comment("Maximum VIN (Volts) the regulator can tolerate before burning out instantly.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxInputVoltage")
                .defineInRange("regulatorMaxInputVoltage", 50.0D, 0.0D, 1000.0D);

        builder.pop();

        builder.comment("Signal Oscillator settings")
                .push("signalOscillator");

        OSCILLATOR_MAX_FREQUENCY = builder
                .comment("Maximum tunable frequency (Hz) for the Signal Oscillator component.",
                        "Hard engine cap is 240 Hz regardless of this value.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".oscillatorMaxFrequency")
                .defineInRange("oscillatorMaxFrequency", 120, 1, 240);

        builder.pop();

        SPEC = builder.build();
    }

    private UsefulComponentsConfig() {
    }
}
