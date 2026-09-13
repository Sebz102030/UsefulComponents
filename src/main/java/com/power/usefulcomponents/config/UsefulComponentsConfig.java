package com.power.usefulcomponents.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration for Useful Components.
 *
 * Built on {@link ModConfigSpec} so it is automatically readable/editable by
 * the "Configured" mod (and any other mod-config-UI that walks ModConfigSpec).
 * All entries include a comment(...) call, which "Configured" surfaces as a
 * tooltip, and translatable-friendly keys via translation(...).
 *
 * Generates: config/useful_componens-common.toml at runtime (NeoForge always
 * persists ModConfigSpec-backed configs as .toml, regardless of the requested
 * file name passed to registerConfig). A hand-authored example of the
 * equivalent JSON view of these defaults is provided separately as
 * useful_componens-common.json for documentation / Configured-JSON-export
 * purposes.
 */
public final class UsefulComponentsConfig {

    public static final ModConfigSpec SPEC;

    // --- Voltage Regulator ---
    public static final ModConfigSpec.DoubleValue REGULATOR_EFFICIENCY;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_TEMP;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_CURRENT;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_VOLTAGE_AT_MAX_CURRENT;
    public static final ModConfigSpec.DoubleValue REGULATOR_MAX_VOLTAGE_AT_1A;

    // --- Signal Oscillator ---
    public static final ModConfigSpec.IntValue OSCILLATOR_MAX_FREQUENCY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Adjustable Voltage Regulator settings")
                .push("voltageRegulator");

        REGULATOR_EFFICIENCY = builder
                .comment("Power efficiency of the voltage regulator (0.0 - 1.0). ",
                        "Lower values generate more waste heat for the same power throughput.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorEfficiency")
                .defineInRange("regulatorEfficiency", 0.8D, 0.0D, 1.0D);

        REGULATOR_MAX_TEMP = builder
                .comment("Maximum working temperature (Celsius) before the regulator burns out.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxTemp")
                .defineInRange("regulatorMaxTemp", 80.0D, 0.0D, 200.0D);

        REGULATOR_MAX_CURRENT = builder
                .comment("Maximum current (Amps) the regulator can pass before burning out.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxCurrent")
                .defineInRange("regulatorMaxCurrent", 2.0D, 0.1D, 50.0D);

        REGULATOR_MAX_VOLTAGE_AT_MAX_CURRENT = builder
                .comment("Maximum safe VOUT (Volts) when current is at regulatorMaxCurrent.",
                        "Used as one endpoint of the linear burnout voltage/current curve.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxVoltageAtMaxCurrent")
                .defineInRange("regulatorMaxVoltageAtMaxCurrent", 30.0D, 0.0D, 500.0D);

        REGULATOR_MAX_VOLTAGE_AT_1A = builder
                .comment("Maximum safe VOUT (Volts) when current is at 1 Amp.",
                        "Used as the other endpoint of the linear burnout voltage/current curve.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".regulatorMaxVoltageAt1A")
                .defineInRange("regulatorMaxVoltageAt1A", 50.0D, 0.0D, 500.0D);

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
