package zcslib.mixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges Mixin class declarations from multiple plugins (and ZCSLIB itself)
 * into a single {@code mixins.zcslib.json} configuration file.
 *
 * <h3>Merge rules</h3>
 * <ul>
 *   <li>Deduplication: the same fully-qualified class name appearing in
 *       multiple plugins is only included once.</li>
 *   <li>Order: ZCSLIB's own Mixins appear first, followed by plugin Mixins
 *       in the order they were scanned (stable across runs).</li>
 *   <li>If no Mixin classes are present, the file is not written.</li>
 * </ul>
 *
 * <h3>Output format</h3>
 * Standard Mixin config JSON compatible with
 * {@code org.spongepowered.asm.mixin.Mixins.addConfiguration()}.
 */
public final class MixinConfigMerger {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULT_PACKAGE = "zcslib.mixin";
    private static final String COMPATIBILITY_LEVEL = "JAVA_21";
    private static final String DEFAULT_REFMAP = "mixins.zcslib.refmap.json";

    private MixinConfigMerger() {}

    // ── Public API ─────────────────────────────────────────

    /**
     * Merge plugin Mixin class lists and ZCSLIB's own Mixins into a
     * single {@link MixinConfig}.
     *
     * @param decls        parsed plugin declarations (may be empty)
     * @param zcslibMixins ZCSLIB's built-in Mixin class names (may be empty)
     * @return the merged configuration (never null)
     */
    public static MixinConfig merge(List<PluginMixinDecl> decls, List<String> zcslibMixins) {
        Set<String> allMixins = new LinkedHashSet<>();

        // ZCSLIB built-ins first
        if (zcslibMixins != null) {
            allMixins.addAll(zcslibMixins);
        }

        // Then plugin Mixins (deduplicated via LinkedHashSet)
        if (decls != null) {
            for (PluginMixinDecl decl : decls) {
                if (decl.mixinClasses() != null) {
                    allMixins.addAll(decl.mixinClasses());
                }
            }
        }

        List<String> mixinList = new ArrayList<>(allMixins);
        LOGGER.info("[ZCSLIB/Mixin] Merged config: {} Mixin class(es) total "
                + "(zcslib={}, plugins={})",
                mixinList.size(),
                zcslibMixins != null ? zcslibMixins.size() : 0,
                decls != null ? decls.size() : 0);

        return new MixinConfig(
                true,
                DEFAULT_PACKAGE,
                COMPATIBILITY_LEVEL,
                Collections.unmodifiableList(mixinList),
                DEFAULT_REFMAP
        );
    }

    /**
     * Write a {@link MixinConfig} as JSON to the given path.
     * Parent directories are created if needed.
     *
     * @param config     the merged configuration
     * @param outputPath where to write {@code mixins.zcslib.json}
     * @throws IOException if writing fails
     */
    public static void write(MixinConfig config, Path outputPath) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("required", config.required());
        root.addProperty("package", config.mixinPackage());
        root.addProperty("compatibilityLevel", config.compatibilityLevel());

        JsonArray mixinsArr = new JsonArray();
        for (String mixin : config.mixins()) {
            mixinsArr.add(mixin);
        }
        root.add("mixins", mixinsArr);
        root.addProperty("refmap", config.refmap());

        Files.createDirectories(outputPath.getParent());

        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }

        LOGGER.info("[ZCSLIB/Mixin] Config written to {} ({} mixin(s))",
                outputPath, config.mixins().size());
    }

    /**
     * Convenience: merge plugin decls + ZCSLIB mixins and write directly.
     *
     * @param decls        parsed plugin declarations
     * @param zcslibMixins ZCSLIB's built-in Mixin class names
     * @param outputPath   where to write the merged JSON
     * @throws IOException if writing fails
     */
    public static void mergeAndWrite(List<PluginMixinDecl> decls,
                                     List<String> zcslibMixins,
                                     Path outputPath) throws IOException {
        MixinConfig config = merge(decls, zcslibMixins);
        write(config, outputPath);
    }

    // ── Refmap Merging ─────────────────────────────────────

    /**
     * Merge multiple per-plugin refmap JSON files into a single
     * {@code mixins.zcslib.refmap.json}.
     *
     * <p>Shallow-merges the {@code "mappings"} block from each source file
     * into the output.  When two files define the same mapping key, the
     * last one wins (latter-wins-latest).
     *
     * @param refmapPaths list of per-plugin refmap file paths to merge
     * @param outputPath  where to write the merged refmap JSON
     * @throws IOException if reading or writing fails
     */
    public static void mergeRefmaps(List<Path> refmapPaths, Path outputPath) throws IOException {
        JsonObject mergedRoot = new JsonObject();
        JsonObject mergedMappings = new JsonObject();
        mergedRoot.add("mappings", mergedMappings);

        Gson parser = new Gson();
        int mergedCount = 0;

        for (Path refmapPath : refmapPaths) {
            if (!Files.exists(refmapPath)) {
                LOGGER.warn("[ZCSLIB/Mixin] Refmap not found for merge: {}", refmapPath);
                continue;
            }
            try (Reader reader = Files.newBufferedReader(refmapPath, StandardCharsets.UTF_8)) {
                JsonObject refmap = parser.fromJson(reader, JsonObject.class);
                if (refmap == null) {
                    continue;
                }
                JsonObject mappings = refmap.getAsJsonObject("mappings");
                if (mappings != null) {
                    for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                        mergedMappings.add(entry.getKey(), entry.getValue());
                    }
                }
                mergedCount++;
            } catch (Exception e) {
                LOGGER.warn("[ZCSLIB/Mixin] Failed to read refmap for merge: {} - {}",
                        refmapPath, e.getMessage());
            }
        }

        Files.createDirectories(outputPath.getParent());
        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            GSON.toJson(mergedRoot, writer);
        }

        LOGGER.info("[ZCSLIB/Mixin] Merged {} refmap(s) into {}", mergedCount, outputPath);
    }
}
