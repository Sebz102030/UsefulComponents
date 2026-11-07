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

    // --- Motion Sensor ---
    public static final ModConfigSpec.DoubleValue SENSOR_BURNOUT_VOLTAGE;
    public static final ModConfigSpec.IntValue SENSOR_MAX_RANGE;
    public static final ModConfigSpec.IntValue SENSOR_MAX_DURATION;
    public static final ModConfigSpec.BooleanValue SENSOR_NEEDS_LINE_OF_SIGHT;
    public static final ModConfigSpec.BooleanValue SENSOR_DETECT_ITEMS;

    // --- UV Curing Machine ---
    public static final ModConfigSpec.IntValue UV_HEAT1_SPEED;
    public static final ModConfigSpec.IntValue UV_HEAT2_SPEED;
    public static final ModConfigSpec.IntValue UV_HEAT3_SPEED;
    public static final ModConfigSpec.DoubleValue UV_HEAT1_POWER;
    public static final ModConfigSpec.DoubleValue UV_HEAT2_POWER;
    public static final ModConfigSpec.DoubleValue UV_HEAT3_POWER;
    public static final ModConfigSpec.DoubleValue UV_COIL_RESISTANCE;
    public static final ModConfigSpec.DoubleValue UV_BURNOUT_VOLTAGE;
    public static final ModConfigSpec.DoubleValue UV_STRESS_IMPACT;

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

        builder.comment("Motion Sensor settings")
                .push("motionSensor");

        SENSOR_BURNOUT_VOLTAGE = builder
                .comment("Supply voltage (VCC - GND, in Volts) at which the motion sensor's own heat",
                        "dissipation reaches its overheat temperature and it burns out (via Power Grid's",
                        "thermal simulation). Below this voltage it settles at a safe temperature.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".sensorBurnoutVoltage")
                .defineInRange("sensorBurnoutVoltage", 6.0D, 1.0D, 100.0D);

        SENSOR_MAX_RANGE = builder
                .comment("Highest detection range (blocks) a motion sensor can be set to.",
                        "Hard cap is 32 regardless of this value.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".sensorMaxRange")
                .defineInRange("sensorMaxRange", 16, 1, 32);

        SENSOR_MAX_DURATION = builder
                .comment("Highest activation duration (seconds) a motion sensor can be set to.",
                        "Hard cap is 120 regardless of this value.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".sensorMaxDuration")
                .defineInRange("sensorMaxDuration", 60, 1, 120);

        SENSOR_NEEDS_LINE_OF_SIGHT = builder
                .comment("If true, solid blocks between the sensor and a moving entity hide that entity from it.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".sensorNeedsLineOfSight")
                .define("sensorNeedsLineOfSight", true);

        SENSOR_DETECT_ITEMS = builder
                .comment("If true, moving item entities are detected too (not only living entities).")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".sensorDetectItems")
                .define("sensorDetectItems", false);

        builder.pop();

        builder.comment("UV Curing Machine settings")
                .push("uvCuring");

        UV_HEAT1_SPEED = builder
                .comment("Minimum rotation speed (RPM, either direction) for heat level 1.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat1Speed")
                .defineInRange("uvHeat1Speed", 16, 1, 256);

        UV_HEAT2_SPEED = builder
                .comment("Minimum rotation speed (RPM, either direction) for heat level 2.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat2Speed")
                .defineInRange("uvHeat2Speed", 64, 1, 256);

        UV_HEAT3_SPEED = builder
                .comment("Minimum rotation speed (RPM, either direction) for heat level 3.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat3Speed")
                .defineInRange("uvHeat3Speed", 128, 1, 256);

        UV_HEAT1_POWER = builder
                .comment("Electrical power (Watts) the lamp must draw for heat level 1.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat1Power")
                .defineInRange("uvHeat1Power", 10.0D, 0.1D, 10000.0D);

        UV_HEAT2_POWER = builder
                .comment("Electrical power (Watts) the lamp must draw for heat level 2.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat2Power")
                .defineInRange("uvHeat2Power", 30.0D, 0.1D, 10000.0D);

        UV_HEAT3_POWER = builder
                .comment("Electrical power (Watts) the lamp must draw for heat level 3.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvHeat3Power")
                .defineInRange("uvHeat3Power", 50.0D, 0.1D, 10000.0D);

        UV_COIL_RESISTANCE = builder
                .comment("Resistance (Ohms) of the UV lamp as seen by the circuit. Power = V^2 / R, so with the",
                        "default 10 Ohm heat level 1 (10 W) needs about 10 V, level 2 (30 W) about 17.3 V",
                        "and level 3 (50 W) about 22.4 V.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvCoilResistance")
                .defineInRange("uvCoilResistance", 10.0D, 0.1D, 100000.0D);

        UV_BURNOUT_VOLTAGE = builder
                .comment("Voltage (Volts across the lamp) at which the UV curing machine burns out and explodes.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvBurnoutVoltage")
                .defineInRange("uvBurnoutVoltage", 60.0D, 1.0D, 100000.0D);

        UV_STRESS_IMPACT = builder
                .comment("Stress impact (SU per RPM) of the machine.")
                .translation("config." + com.power.usefulcomponents.UsefulComponents.MODID
                        + ".uvStressImpact")
                .defineInRange("uvStressImpact", 4.0D, 0.0D, 1024.0D);

        builder.pop();

        SPEC = builder.build();
    }

    private UsefulComponentsConfig() {
    }
}
