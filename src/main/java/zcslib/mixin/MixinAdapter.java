package zcslib.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Mixin 适配器入口 — 编排插件 Mixin 的扫描、refmap 重写、config 合并与注册。
 *
 * <h3>调用时机</h3>
 * 由 {@link zcslib.ZCSLIB} 在 {@code @Mod} 构造期调用，早于所有子系统和类加载。
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>{@link #detectMCVersion()} — 检测当前运行时 MC 版本</li>
 *   <li>{@link MappingTable#load(String)} — 加载版本映射表</li>
 *   <li>{@link #scanPlugins(Path)} — 扫描插件 JAR 内的 {@code zcslib.mixin.json}</li>
 *   <li>{@link RefmapResolver#resolve(Path, MappingTable)} — 重写 refmap obf 名</li>
 *   <li>{@link MixinConfigMerger#mergeAndWrite(List, List, Path)} — 合并写入配置</li>
 *   <li>{@code Mixins.addConfiguration()} — 注册给 Mixin 框架</li>
 * </ol>
 *
 * <p>零 MC 依赖（纯 Java JSON/文件 I/O），唯一外部依赖为
 * {@code org.spongepowered.asm.mixin.Mixins}（compileOnly，NeoForge 运行时提供）。
 */
public final class MixinAdapter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** JAR 根目录下的插件 Mixin 声明文件名。 */
    private static final String PLUGIN_MIXIN_JSON = "zcslib.mixin.json";

    /** 插件 refmap 存放于 JAR 内 META-INF 目录的模式。 */
    private static final String REFMAP_DIR = "META-INF";

    private MixinAdapter() {}

    // ── Bootstrap ──────────────────────────────────────────

    /**
     * 由 {@code ZCSLIB.java} 在 {@code @Mod} 构造期调用。
     *
     * <p>扫描插件 JAR、重写 refmap、合并 config、注册给 Mixin 框架。
     * 任何步骤的异常都会被捕获并记录，不会中断 Mod 加载流程。
     *
     * @param pluginsDir 插件目录 ({@code config/DLZstudio/ZCSLIB/plugins/})
     */
    public static void bootstrap(Path pluginsDir) {
        LOGGER.info("[ZCSLIB/Mixin] MixinAdapter bootstrapping — plugins dir: {}", pluginsDir);

        try {
            // 1. 检测当前 MC 版本
            String mcVersion = detectMCVersion();
            LOGGER.info("[ZCSLIB/Mixin] Detected MC version: {}", mcVersion);

            // 2. 加载运行时的版本映射表
            MappingTable runtimeMappings;
            try {
                runtimeMappings = MappingTable.load(mcVersion);
            } catch (IOException e) {
                LOGGER.warn("[ZCSLIB/Mixin] Failed to load mapping table for {}: {}. "
                        + "Skipping Mixin bootstrap.", mcVersion, e.getMessage());
                return;
            }

            // 3. 扫描 plugins/ → 收集 Mixin 声明
            List<PluginMixinDecl> decls;
            try {
                decls = scanPlugins(pluginsDir);
            } catch (IOException e) {
                LOGGER.warn("[ZCSLIB/Mixin] Plugin scan failed: {}. Skipping Mixin bootstrap.",
                        e.getMessage());
                return;
            }

            if (decls.isEmpty()) {
                LOGGER.info("[ZCSLIB/Mixin] No plugin Mixin declarations found. "
                        + "Skipping Mixin registration.");
                return;
            }

            // 4. 对每个插件: 读 refmap → 重写 obf
            List<String> allMixinClasses = new ArrayList<>();
            Path configDir = pluginsDir.getParent(); // config/DLZstudio/ZCSLIB/
            if (configDir == null) {
                LOGGER.error("[ZCSLIB/Mixin] Cannot determine config directory from plugins dir.");
                return;
            }
            List<Path> resolvedRefmapPaths = new ArrayList<>();

            for (PluginMixinDecl decl : decls) {
                allMixinClasses.addAll(decl.mixinClasses());

                if (decl.refmap() == null) {
                    LOGGER.info("[ZCSLIB/Mixin] Plugin '{}' has no refmap; skipping resolve.",
                            decl.pluginId());
                    continue;
                }

                try {
                    RefmapResolver resolved =
                            RefmapResolver.resolve(decl.refmap(), runtimeMappings);
                    Path writtenPath = resolved.writeTo(configDir, decl.pluginId());
                    resolvedRefmapPaths.add(writtenPath);
                } catch (IOException e) {
                    LOGGER.warn("[ZCSLIB/Mixin] Failed to resolve refmap for '{}': {}",
                            decl.pluginId(), e.getMessage());
                }
            }

            // 4b. Merge all per-plugin refmaps into single mixins.zcslib.refmap.json
            //     Mixin config always references this file — must exist even if empty.
            Path mergedRefmapPath = configDir.resolve("mixins.zcslib.refmap.json");
            if (!resolvedRefmapPaths.isEmpty()) {
                try {
                    MixinConfigMerger.mergeRefmaps(resolvedRefmapPaths, mergedRefmapPath);
                } catch (IOException e) {
                    LOGGER.warn("[ZCSLIB/Mixin] Failed to merge refmaps: {}", e.getMessage());
                }
            }
            // If no refmaps were resolved (e.g. all plugins had missing/broken refmaps),
            // create an empty placeholder — the Mixin config references this file and
            // will crash on startup if it's absent.
            if (!Files.exists(mergedRefmapPath)) {
                try {
                    Files.writeString(mergedRefmapPath, "{\"mappings\":{}}");
                    LOGGER.info("[ZCSLIB/Mixin] Created empty refmap (no plugin refmaps merged)");
                } catch (IOException e) {
                    LOGGER.warn("[ZCSLIB/Mixin] Failed to create empty refmap: {}", e.getMessage());
                }
            }

            if (allMixinClasses.isEmpty()) {
                LOGGER.info("[ZCSLIB/Mixin] No Mixin classes to register after resolution.");
                return;
            }

            // 5. 合并 config
            Path configPath = configDir.resolve("mixins.zcslib.json");

            List<String> zcslibBuiltinMixins = getZcslibMixins();
            MixinConfigMerger.mergeAndWrite(decls, zcslibBuiltinMixins, configPath);

            // 6. 注册给 Mixin 框架
            //    Note: Mixins.addConfiguration() triggers immediate config loading which
            //    throws MixinInitialisationError (extends Error, not Exception) when the
            //    file path is outside the classpath/GameDir resource tree. Since ZCSLIB
            //    generates this config at runtime under config/DLZstudio/ZCSLIB/, Mixin
            //    cannot resolve it during early mod construction.
            //    The merged config and refmap are correctly written and available for
            //    reference. Full registration will be addressed post-@Mod construction.
            try {
                org.spongepowered.asm.mixin.Mixins.addConfiguration(
                        configPath.toAbsolutePath().toString());
                LOGGER.info("[ZCSLIB/Mixin] Registered mixins.zcslib.json with Mixin framework. "
                        + "{} mixin class(es) total.", allMixinClasses.size());
            } catch (Throwable t) {
                LOGGER.warn("[ZCSLIB/Mixin] Runtime Mixin config registration deferred. "
                        + "Config written to {} but Mixin framework cannot load "
                        + "non-classpath configs during @Mod construction. "
                        + "Mixin classes: {}", configPath, allMixinClasses);
            }
        } catch (Throwable t) {
            LOGGER.error("[ZCSLIB/Mixin] Bootstrap failed: {}", t.getMessage(), t);
        }
    }

    // ── Version Detection ──────────────────────────────────

    /**
     * 检测当前运行时 MC/NeoForge 版本。
     *
     * <p>参考 {@link zcslib.persistence.NbtBridge} 的模式：
     * 21.1–25.x 可通过 {@code FMLLoader.versionInfo().neoForgeVersion()} 获取；
     * 26.1+ 该 API 被移除，回退到 {@code "26.1"}。
     *
     * @return 版本字符串，如 {@code "21.1"} 或 {@code "26.1"}
     */
    static String detectMCVersion() {
        try {
            // 21.1–25.x: FMLLoader.versionInfo() exists
            String full = FMLLoader.versionInfo().neoForgeVersion();
            // Parse "21.1.228" → "21.1"
            int firstDot = full.indexOf('.');
            if (firstDot < 0) {
                return full;
            }
            int secondDot = full.indexOf('.', firstDot + 1);
            return secondDot > 0 ? full.substring(0, secondDot) : full;
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // 26.1+: versionInfo() removed from FMLLoader
            LOGGER.info("[ZCSLIB/Mixin] FMLLoader.versionInfo() not available; "
                    + "assuming 26.1+ runtime.");
            return "26.1";
        }
    }

    // ── Plugin Scanning ────────────────────────────────────

    /**
     * 扫描 {@code pluginsDir} 下所有 JAR 文件，读取每个 JAR 根目录的
     * {@code zcslib.mixin.json}。
     *
     * <p>不存在的目录或非 JAR 文件会被静默跳过。
     *
     * @param dir 插件目录
     * @return 解析出的 Mixin 声明列表（可能为空）
     * @throws IOException 如果目录遍历失败
     */
    static List<PluginMixinDecl> scanPlugins(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            LOGGER.info("[ZCSLIB/Mixin] Plugins directory does not exist: {}", dir);
            return Collections.emptyList();
        }

        List<PluginMixinDecl> result = new ArrayList<>();
        List<Path> jars;

        try (Stream<Path> entries = Files.list(dir)) {
            jars = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList();
        }

        if (jars.isEmpty()) {
            LOGGER.info("[ZCSLIB/Mixin] No JAR files found in plugins directory.");
            return Collections.emptyList();
        }

        for (Path jarPath : jars) {
            try {
                PluginMixinDecl decl = readJarMixinDecl(jarPath);
                if (decl != null) {
                    result.add(decl);
                }
            } catch (Exception e) {
                LOGGER.warn("[ZCSLIB/Mixin] Failed to read Mixin declaration from {}: {}",
                        jarPath.getFileName(), e.getMessage());
            }
        }

        LOGGER.info("[ZCSLIB/Mixin] Scanned {} JAR(s), found {} Mixin declaration(s).",
                jars.size(), result.size());
        return Collections.unmodifiableList(result);
    }

    /**
     * 从单个 JAR 文件中读取 Mixin 声明。
     *
     * @param jarPath JAR 文件路径
     * @return 解析的声明，如果 JAR 内无 {@code zcslib.mixin.json} 则返回 {@code null}
     * @throws IOException 如果 JAR 读取失败
     */
    private static PluginMixinDecl readJarMixinDecl(Path jarPath) throws IOException {
        URI jarUri = URI.create("jar:" + jarPath.toUri());

        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
            Path declFile = fs.getPath("/", PLUGIN_MIXIN_JSON);
            if (!Files.exists(declFile)) {
                return null; // 插件无 Mixin — 静默跳过
            }

            // 读取声明 JSON
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(declFile, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonObject.class);
            } catch (JsonParseException e) {
                throw new IOException("Malformed " + PLUGIN_MIXIN_JSON, e);
            }

            if (root == null) {
                throw new IOException("Empty " + PLUGIN_MIXIN_JSON);
            }

            String pluginId = root.has("pluginId")
                    ? root.get("pluginId").getAsString()
                    : jarPath.getFileName().toString().replace(".jar", "");

            List<String> mixins = new ArrayList<>();
            JsonArray mixinsArr = root.getAsJsonArray("mixins");
            if (mixinsArr != null) {
                for (JsonElement elem : mixinsArr) {
                    mixins.add(elem.getAsString());
                }
            }

            if (mixins.isEmpty()) {
                LOGGER.info("[ZCSLIB/Mixin] Plugin '{}' declares no Mixin classes.", pluginId);
                return null;
            }

            // 定位 refmap: META-INF/{pluginId}.refmap.json
            Path refmapPath = fs.getPath("/", REFMAP_DIR, pluginId + ".refmap.json");
            if (!Files.exists(refmapPath)) {
                LOGGER.warn("[ZCSLIB/Mixin] Plugin '{}' has no refmap at {}/{}.refmap.json. "
                        + "Mixin classes may not inject correctly.", pluginId, REFMAP_DIR, pluginId);
            }

            LOGGER.info("[ZCSLIB/Mixin] Plugin '{}' declares {} Mixin class(es): {}",
                    pluginId, mixins.size(), mixins);

            // 抽取 refmap: copy directly while the JAR FileSystem is still open
            // (avoid re-opening the JAR which can fail on some platforms)
            Path effectiveRefmap = null;
            if (Files.exists(refmapPath)) {
                Path pluginsDir = jarPath.getParent();
                if (pluginsDir != null) {
                    Path configDir = pluginsDir.getParent();
                    if (configDir != null) {
                        try {
                            Files.createDirectories(configDir);
                            Path dest = configDir.resolve(pluginId + ".refmap.json");
                            Files.copy(refmapPath, dest,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            effectiveRefmap = dest;
                            LOGGER.debug("[ZCSLIB/Mixin] Extracted refmap: {}", dest);
                        } catch (IOException e) {
                            LOGGER.warn("[ZCSLIB/Mixin] Failed to extract refmap for '{}': {}",
                                    pluginId, e.getMessage());
                        }
                    }
                }
            }

            return new PluginMixinDecl(pluginId, effectiveRefmap, Collections.unmodifiableList(mixins));
        }
    }

    // ── ZCSLIB Built-in Mixins ─────────────────────────────

    /**
     * Return ZCSLIB's own Mixin class names.
     * Currently empty — add entries here when ZCSLIB ships its own Mixins.
     */
    private static List<String> getZcslibMixins() {
        // Placeholder for future ZCSLIB built-in Mixins
        return Collections.emptyList();
    }
}
