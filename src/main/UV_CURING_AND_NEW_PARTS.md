# New parts

## UV curing machine (`usefulcomponents:uv_curing_machine`)
Press-style machine. Needs shaft rotation AND electrical power (power cord or device
connector on top / the two non-shaft sides, like the basin heater).
- < 32 RPM: does nothing
- 32 .. 127 RPM: needs 10 W      - 128+ RPM: needs 20 W
All numbers + lamp resistance are in `usefulcomponents-common.toml` (`uvCuring`).
Power = V^2 / R, default R = 10 ohm -> ~10 V for the 10 W tier, ~14.2 V for 20 W.

Standalone recipe (item under the machine, same JSON shape as Power Grid's magnetization):
```json
{ "type": "usefulcomponents:uv_curing",
  "ingredients": [ { "item": "minecraft:slime_ball" } ],
  "results": [ { "item": "minecraft:lime_dye", "count": 1 } ] }
```
As a sequenced-assembly step, use the same object inside `"sequence"`
(the ingredient and result are the transitional item), e.g.:
```json
{ "type": "usefulcomponents:uv_curing",
  "ingredients": [ { "item": "usefulcomponents:incomplete_oscillator" } ],
  "results": [ { "item": "usefulcomponents:incomplete_oscillator" } ] }
```

## Motion sensor (board component)
Pins: GND, VCC, OUT. OUT - GND = VCC - GND while motion is latched. Footprint 3x2,
rotates with the component (arrow in the design table shows where it looks).
Settings: range (blocks), duration (s). Burns out via ThermalBuilder at
`motionSensor.sensorBurnoutVoltage` (default 6 V).

## 10-pin ribbon connectors
`ribbon_connector_10_side` / `_front`. Use the normal Ribbon Cable item; a cable only
joins connectors with the same pin count.
