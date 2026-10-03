package com.power.usefulcomponents.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.power.usefulcomponents.UsefulComponents;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the ribbon cable's visual definition from
 * {@code assets/usefulcomponents/models/ribbon_cable.json}. Format is a small
 * subset of vanilla's own block-model schema:
 *
 * <pre>{@code
 * {
 *   "thickness": 0.25,
 *   "texture_size": 4,
 *   "textures": {
 *     "end":    "usefulcomponents:block/component/ribbon_cable_end",
 *     "middle": "usefulcomponents:block/component/ribbon_cable_middle"
 *   },
 *   "elements": [
 *     { "faces": {
 *         "middle":  { "uv": [0, 0, 4, 4], "texture": "#middle" },
 *         "end":     { "uv": [0, 0, 4, 2], "texture": "#end" },
 *         "endflip": { "uv": [4, 2, 0, 0], "texture": "#end" }
 *     } }
 *   ]
 * }
 * }</pre>
 *
 * "thickness" is the ribbon thickness in PIXELS (1 px = 1/16 block), so
 * 0.25 is a quarter of a pixel.
 * "uv" values are in PIXELS of the face's own texture (so {@code [0,0,4,2]}
 * on a 4x2 texture is the whole texture, {@code [0,0,4,4]} on a 4x4 texture
 * likewise); the real png size is read when the config loads, and
 * "texture_size" is only the fallback size if a png can't be read.
 * The uv rect's size in pixels is also the ribbon piece's real length in the
 * world (1 px = 1/16 block), so a 4x2 end texture draws a 2 px long cap and
 * a 4x4 middle texture draws 4 px long tiles, always at 1:1 pixel density.
 *
 * "end" is used for the first connector's end-cap, "endflip" for the second
 * connector's end-cap (reversed uv order mirrors/rotates it so the two ends
 * don't look identically oriented). "middle" is tiled to fill the run
 * between the two end-caps. Texture ids use the model-style form
 * ({@code namespace:block/...}); the renderer turns them into the real
 * texture file path.
 */
public final class RibbonCableConfig {

    public record Face(ResourceLocation texture, float u0, float v0, float u1, float v1, float lengthPx) {
        /** World length (in blocks) of one full run of this face, at 1:1 texel density. */
        public float length() {
            return lengthPx / 16f;
        }

        /** Maps a local 0-1 fraction along U into this face's own uv sub-rect. */
        public float u(float localU) {
            return u0 + (u1 - u0) * localU;
        }

        /** Maps a local 0-1 fraction along V into this face's own uv sub-rect. */
        public float v(float localV) {
            return v0 + (v1 - v0) * localV;
        }
    }

    public final float thickness;
    public final Face end;
    public final Face endFlip;
    public final Face middle;

    private RibbonCableConfig(float thickness, Face end, Face endFlip, Face middle) {
        this.thickness = thickness;
        this.end = end;
        this.endFlip = endFlip;
        this.middle = middle;
    }

    private static final ResourceLocation CONFIG_LOCATION =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "models/ribbon_cable.json");

    @Nullable
    private static RibbonCableConfig cached;

    public static RibbonCableConfig get() {
        if (cached == null)
            cached = load();
        return cached;
    }

    private static RibbonCableConfig load() {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(CONFIG_LOCATION);
            try (Reader reader = resource.openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

                Map<String, ResourceLocation> textures = new HashMap<>();
                JsonObject texturesJson = GsonHelper.getAsJsonObject(json, "textures");
                for (var entry : texturesJson.entrySet()) {
                    textures.put(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString()));
                }

                float thickness = GsonHelper.getAsFloat(json, "thickness");
                float textureSize = GsonHelper.getAsFloat(json, "texture_size", 16f);
                if (textureSize <= 0f)
                    throw new IllegalArgumentException("texture_size must be > 0, got: " + textureSize);

                JsonObject element = json.getAsJsonArray("elements").get(0).getAsJsonObject();
                JsonObject faces = GsonHelper.getAsJsonObject(element, "faces");

                Face end = parseFace(faces.getAsJsonObject("end"), textures, textureSize);
                Face endFlip = parseFace(faces.getAsJsonObject("endflip"), textures, textureSize);
                Face middle = parseFace(faces.getAsJsonObject("middle"), textures, textureSize);

                return new RibbonCableConfig(thickness, end, endFlip, middle);
            }
        } catch (Exception e) {
            UsefulComponents.LOGGER.warn("Could not load {} - falling back to default ribbon cable look",
                    CONFIG_LOCATION, e);
            ResourceLocation endTex = ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_end");
            ResourceLocation midTex = ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_middle");
            return new RibbonCableConfig(0.25f,
                    new Face(endTex, 0f, 0f, 1f, 1f, 2f),
                    new Face(endTex, 1f, 1f, 0f, 0f, 2f),
                    new Face(midTex, 0f, 0f, 1f, 1f, 4f));
        }
    }

    private static Face parseFace(JsonObject faceJson, Map<String, ResourceLocation> textures, float textureSize) {
        var uv = GsonHelper.getAsJsonArray(faceJson, "uv");
        float pu0 = uv.get(0).getAsFloat();
        float pv0 = uv.get(1).getAsFloat();
        float pu1 = uv.get(2).getAsFloat();
        float pv1 = uv.get(3).getAsFloat();

        String textureRef = GsonHelper.getAsString(faceJson, "texture");
        if (!textureRef.startsWith("#"))
            throw new IllegalArgumentException("Face texture must be a \"#<key>\" reference, got: " + textureRef);
        ResourceLocation texture = textures.get(textureRef.substring(1));
        if (texture == null)
            throw new IllegalArgumentException("No texture defined for key: " + textureRef);

        float[] size = textureDimensions(texture, textureSize);
        return new Face(texture, pu0 / size[0], pv0 / size[1], pu1 / size[0], pv1 / size[1], Math.abs(pv1 - pv0));
    }

    /** Real png size in pixels ({width, height}), or the json's texture_size for both if it can't be read. */
    private static float[] textureDimensions(ResourceLocation id, float fallback) {
        String path = id.getPath();
        if (!path.startsWith("textures/"))
            path = "textures/" + path;
        if (!path.endsWith(".png"))
            path = path + ".png";
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
        try (InputStream in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(file).open();
             NativeImage image = NativeImage.read(in)) {
            return new float[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            UsefulComponents.LOGGER.warn("Could not read size of {} - assuming {}x{}", file, fallback, fallback, e);
            return new float[]{fallback, fallback};
        }
    }

    /** For tests/tools that need to force a reload after editing the json in-place. */
    public static void invalidate() {
        cached = null;
    }
}
