package com.power.usefulcomponents.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.power.usefulcomponents.UsefulComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and holds the data-driven *look* of the ribbon cable - which
 * textures are used for the end caps and the repeating middle section, how
 * thick the cable is, and (optionally) which pixel region of each texture
 * to sample.
 * <p>
 * This class only knows how to parse the config file and hand back plain
 * data. It never touches vertices/quads - see {@link RibbonCableRenderer}
 * for that. Keeping the two apart means the render *definition* (this
 * file's format) and the render *code* (the mesh building) live in
 * separate files, as opposed to one file doing both.
 * <p>
 * Resource location: {@code assets/usefulcomponents/ribbon_cable.json}
 * <pre>{@code
 * {
 *   "thickness": 0.25,
 *   "texture_size": 16,
 *   "textures": {
 *     "end": "usefulcomponents:block/component/ribbon_cable_end",
 *     "middle": "usefulcomponents:block/component/ribbon_cable_middle"
 *   },
 *   "elements": [
 *     {
 *       "faces": {
 *         "middle":  { "uv": [4, 0, 6, 4], "texture": "#middle" },
 *         "end":     { "uv": [0, 0, 4, 4], "texture": "#end" },
 *         "endflip": { "uv": [4, 4, 0, 4], "texture": "#end" }
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 * <ul>
 *     <li>{@code thickness} - cable thickness in the usual 1/16ths-of-a-block units (required).</li>
 *     <li>{@code texture_size} - the pixel grid "uv" rectangles are measured against, same
 *         convention as vanilla block models (optional, defaults to 16).</li>
 *     <li>{@code textures} - named texture aliases, referenced from a face via {@code "#name"}.
 *         A face's {@code texture} can also just be a full resource location directly.</li>
 *     <li>{@code elements[0].faces.end} - the cap drawn at the first connector.</li>
 *     <li>{@code elements[0].faces.endflip} - the cap drawn at the second connector. Optional,
 *         falls back to {@code end} when absent, so you only need it if that end should look
 *         different (e.g. a mirrored connector texture).</li>
 *     <li>{@code elements[0].faces.middle} - the repeating tile used to fill the gap between
 *         the two caps.</li>
 * </ul>
 * Any parse failure (missing file, bad JSON, wrong types) logs a warning and falls back to
 * the mod's built-in default textures/thickness rather than crashing rendering.
 */
public final class CableRenderConfig {
    private static final ResourceLocation CONFIG_LOCATION =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "ribbon_cable.json");

    private static final Face DEFAULT_END = new Face(
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_end"),
            0f, 0f, 1f, 1f);
    private static final Face DEFAULT_MIDDLE = new Face(
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_middle"),
            0f, 0f, 1f, 1f);
    private static final CableRenderConfig DEFAULT =
            new CableRenderConfig(0.25f, DEFAULT_END, DEFAULT_END, DEFAULT_MIDDLE);

    @Nullable
    private static CableRenderConfig cached;

    /** One textured face: which sprite to bind, and the UV rectangle (already normalized to 0..1) to sample. */
    public record Face(ResourceLocation texture, float u0, float v0, float u1, float v1) {
    }

    private final float thickness;
    private final Face end;
    private final Face endFlip;
    private final Face middle;

    private CableRenderConfig(float thickness, Face end, Face endFlip, Face middle) {
        this.thickness = thickness;
        this.end = end;
        this.endFlip = endFlip;
        this.middle = middle;
    }

    public float thickness() {
        return thickness;
    }

    /** Cap drawn at the first (lower-UUID) connector. */
    public Face end() {
        return end;
    }

    /** Cap drawn at the second (higher-UUID) connector. Falls back to {@link #end()} if not configured. */
    public Face endFlip() {
        return endFlip;
    }

    /** Repeating tile used to fill the straight run between the two caps. */
    public Face middle() {
        return middle;
    }

    public static CableRenderConfig get() {
        if (cached == null)
            cached = load();
        return cached;
    }

    /** Drops the cached config so it's re-parsed next time {@link #get()} is called (e.g. after a resource reload). */
    public static void invalidate() {
        cached = null;
    }

    private static CableRenderConfig load() {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(CONFIG_LOCATION);
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                float thickness = GsonHelper.getAsFloat(root, "thickness");
                float textureSize = GsonHelper.getAsFloat(root, "texture_size", 16f);

                Map<String, ResourceLocation> textures = new HashMap<>();
                if (root.has("textures")) {
                    for (var entry : root.getAsJsonObject("textures").entrySet())
                        textures.put(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString()));
                }

                JsonObject faces = root.getAsJsonArray("elements")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("faces");

                Face end = parseFace(faces, "end", textures, textureSize, DEFAULT_END);
                Face middle = parseFace(faces, "middle", textures, textureSize, DEFAULT_MIDDLE);
                Face endFlip = faces.has("endflip")
                        ? parseFace(faces, "endflip", textures, textureSize, end)
                        : end;

                return new CableRenderConfig(thickness, end, endFlip, middle);
            }
        } catch (Exception e) {
            UsefulComponents.LOGGER.warn("Could not load {} - falling back to default ribbon cable textures/thickness",
                    CONFIG_LOCATION, e);
            return DEFAULT;
        }
    }

    private static Face parseFace(JsonObject faces, String name, Map<String, ResourceLocation> textures,
                                   float textureSize, Face fallback) {
        if (!faces.has(name))
            return fallback;
        JsonObject face = faces.getAsJsonObject(name);

        ResourceLocation texture = fallback.texture();
        if (face.has("texture")) {
            String ref = face.get("texture").getAsString();
            texture = ref.startsWith("#")
                    ? textures.getOrDefault(ref.substring(1), fallback.texture())
                    : ResourceLocation.parse(ref);
        }

        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
        if (face.has("uv")) {
            var uv = face.getAsJsonArray("uv");
            u0 = uv.get(0).getAsFloat() / textureSize;
            v0 = uv.get(1).getAsFloat() / textureSize;
            u1 = uv.get(2).getAsFloat() / textureSize;
            v1 = uv.get(3).getAsFloat() / textureSize;
        }
        return new Face(texture, u0, v0, u1, v1);
    }
}
