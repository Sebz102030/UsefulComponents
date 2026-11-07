# New parts

## UV curing machine (`usefulcomponents:uv_curing_machine`)
Press-style machine. Needs shaft rotation AND electrical power (power cord or device
connector on top / the two non-shaft sides, like the basin heater).
- Heat level = the LOWER of what the lamp power and the shaft speed allow:
  level 1 = 10 W + 16 RPM, level 2 = 30 W + 64 RPM, level 3 = 50 W + 128 RPM
  (a higher level also satisfies a recipe that needs a lower one).
- Goggles warn "Rotation too slow for level N" when the power would allow a higher level than the speed does.
- Burnout awards the Power Grid "blow up" advancement, like other electric blocks.
- JEI: a "UV Curing" tab lists every recipe with its heat level stage (power + RPM) and the curing time.
- At 60 V across the lamp the machine burns out and explodes.
- Goggles show the heat level as a bar (like Create's stress display); the item tooltip shows
  Create's stress impact (4 SU per RPM by default) and the electrical properties (hold shift).
All numbers + lamp resistance are in `usefulcomponents-common.toml` (`uvCuring`).
Power = V^2 / R, default R = 10 ohm -> ~10 V / ~17.3 V / ~22.4 V for levels 1 / 2 / 3.

Standalone recipe (item under the machine, same JSON shape as Power Grid's magnetization,
plus two optional keys):
```json
{ "type": "usefulcomponents:uv_curing",
  "ingredients": [ { "item": "minecraft:slime_ball" } ],
  "results": [ { "id": "minecraft:lime_dye", "count": 1 } ],
  "curing_time": 100,
  "heat_level": 1 }
```
- `curing_time`: ticks the head stays parked over the item (default 100, i.e. 5 s). The clock only
  runs while the lamp is at least at the recipe's heat level.
- `heat_level`: 1-3 (default 1).

As a sequenced-assembly step, use the same object inside `"sequence"`
(the ingredient and result are the transitional item), e.g.:
```json
{ "type": "usefulcomponents:uv_curing",
  "ingredients": [ { "item": "usefulcomponents:incomplete_oscillator" } ],
  "results": [ { "id": "usefulcomponents:incomplete_oscillator" } ],
  "curing_time": 200,
  "heat_level": 2 }
```

## Motion sensor (board component)
Pins: GND, VCC, OUT. OUT - GND = VCC - GND while motion is latched. Footprint 3x2,
rotates with the component (arrow in the design table shows where it looks).
Settings: range (blocks), duration (s). Burns out via ThermalBuilder at
`motionSensor.sensorBurnoutVoltage` (default 6 V).

## 10-pin ribbon connectors
`ribbon_connector_10_side` / `_front`. Use the normal Ribbon Cable item; a cable only
joins connectors with the same pin count.


## Replacing the UV curing machine's models and textures
The machine is drawn from three model files, in the world AND in the JEI tab / sequenced-assembly JEI page
(both are animated automatically, like Create's press - nothing else to change):

| Part | File (assets/usefulcomponents/) | Built | Animated by |
|---|---|---|---|
| Body (everything static) | models/block/uv_curing_machine.json (+ blockstates/uv_curing_machine.json) | facing NORTH | - |
| Head (lamp) | models/block/uv_curing_head.json | facing NORTH, at its resting position | moves down / parks 4 px above the item / retracts |
| Shaft input | models/block/uv_curing_shaft.json | along the Z axis, centered | spins around Z |

Textures go in textures/block/. A resource pack can override the same paths. The default shaft uses
Create's `create:block/axis` and `create:block/axis_top` textures.

## Recipe time
`curing_time` in the recipe json is in TICKS (20 per second). JEI shows it in the most readable unit
(ticks, seconds, minutes, hours) with the exact ticks in brackets, e.g. "1 min 30 s (1800 ticks)".
