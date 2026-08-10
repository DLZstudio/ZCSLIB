package zcslib.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Version-aware mapping table: loads Mojang &rarr; obfuscated name mappings
 * for a specific Minecraft version.
 *
 * <p>Loaded from {@code mappings/{version}.json} on the classpath.
 * Provides forward (Mojang &rarr; obf) and reverse (obf &rarr; Mojang) lookups
 * for classes, methods, and fields.
 *
 * <p>Also supports {@code classRenames} — cross-version API renames that are
 * <em>not</em> obfuscation (e.g. {@code ResourceLocation} &rarr; {@code Identifier}
 * in 26.1 where Mojang removed obfuscation entirely).
 *
 * <p>All queries return {@link Optional#empty()} when no mapping entry exists,
 * signaling the caller to keep the original name unchanged.
 */
public final class MappingTable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final String version;
    private final boolean base;

    //  Mojang class name → ObfClassEntry
    private final Map<String, ObfClassEntry> classForward = new HashMap<>();

    //  obf class name → Mojang class name (reverse)
    private final Map<String, String> classReverse = new HashMap<>();

    //  Cross-version API renames (not obfuscation): old Mojang name → new current name
    private final Map<String, String> classRenameForward = new HashMap<>();

    //  Reverse: new current name → old Mojang name
    private final Map<String, String> classRenameReverse = new HashMap<>();

    private MappingTable(String version, boolean base) {
        this.version = version;
        this.base = base;
    }

    // ── Public API ─────────────────────────────────────────

    /**
     * Load the mapping table for {@code mcVersion} from the classpath resource
     * {@code /DLZstudio/ZCSLIB/mappings/{mcVersion}.json}.
     *
     * @param mcVersion version string, e.g. {@code "21.1"} or {@code "26.1"}
     * @return the loaded mapping table (never null; may be empty for baseline versions)
     * @throws IOException if the resource cannot be read
     * @throws JsonParseException if the JSON is malformed
     */
    public static MappingTable load(String mcVersion) throws IOException {
        String resourcePath = "/DLZstudio/ZCSLIB/mappings/" + mcVersion + ".json";
        LOGGER.info("[ZCSLIB/Mixin] Loading mapping table from {}", resourcePath);

        try (InputStream is = MappingTable.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Mapping table not found on classpath: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return parse(mcVersion, reader);
            }
        }
    }

    /**
     * Load the mapping table from a file path (for testing / external use).
     *
     * @param mcVersion version string
     * @param filePath  path to the JSON file
     * @return the loaded mapping table
     * @throws IOException if the file cannot be read
     */
    public static MappingTable loadFromFile(String mcVersion, Path filePath) throws IOException {
        LOGGER.info("[ZCSLIB/Mixin] Loading mapping table from file {}", filePath);
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return parse(mcVersion, reader);
        }
    }

    /** @return the version this table targets, e.g. {@code "21.1"} */
    public String version() {
        return version;
    }

    /** @return true if this is a baseline table (obf names equal Mojang names) */
    public boolean isBase() {
        return base;
    }

    // ── Forward lookups (Mojang → obf) ─────────────────────

    /**
     * Look up the obfuscated class name for a given Mojang class name.
     *
     * @param mojangName internal (Mojang) class name, e.g. {@code "net/minecraft/world/item/ItemStack"}
     * @return the obfuscated name, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getClassObf(String mojangName) {
        ObfClassEntry entry = classForward.get(mojangName);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.obfName());
    }

    /**
     * Look up the obfuscated method name for a given Mojang method descriptor.
     *
     * @param mojangClass Mojang class name
     * @param mojangDesc  method descriptor in {@code "methodName (argTypes)returnType"} format
     * @return the obfuscated name, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getMethodObf(String mojangClass, String mojangDesc) {
        ObfClassEntry entry = classForward.get(mojangClass);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.methods().get(mojangDesc));
    }

    /**
     * Look up the obfuscated field name for a given Mojang field name.
     *
     * @param mojangClass Mojang class name
     * @param mojangField Mojang field name
     * @return the obfuscated name, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getFieldObf(String mojangClass, String mojangField) {
        ObfClassEntry entry = classForward.get(mojangClass);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.fields().get(mojangField));
    }

    // ── Reverse lookups (obf → Mojang) ─────────────────────

    /**
     * Look up the Mojang class name for a given obfuscated class name.
     *
     * @param obfName obfuscated class name
     * @return the Mojang/internal name, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getClassMojang(String obfName) {
        return Optional.ofNullable(classReverse.get(obfName));
    }

    /**
     * Find the Mojang method descriptor for a given obfuscated method name in a class.
     *
     * @param mojangClass Mojang class name
     * @param obfMethod   obfuscated method name, e.g. {@code "m_41620"}
     * @return the Mojang method descriptor, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getMethodMojang(String mojangClass, String obfMethod) {
        ObfClassEntry entry = classForward.get(mojangClass);
        if (entry == null || entry.methodReverse() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.methodReverse().get(obfMethod));
    }

    /**
     * Find the Mojang field name for a given obfuscated field name in a class.
     *
     * @param mojangClass Mojang class name
     * @param obfField    obfuscated field name, e.g. {@code "f_41621"}
     * @return the Mojang field name, or {@link Optional#empty()} if no mapping
     */
    public Optional<String> getFieldMojang(String mojangClass, String obfField) {
        ObfClassEntry entry = classForward.get(mojangClass);
        if (entry == null || entry.fieldReverse() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.fieldReverse().get(obfField));
    }

    // ── Cross-version class renames (not obfuscation) ──────

    /**
     * Look up the current class name for a given older Mojang class name
     * that has been renamed in this version via an API-level rename (not obfuscation).
     *
     * <p>This is separate from {@link #getClassObf(String)} because these renames
     * represent deliberate Mojang API changes (e.g. {@code ResourceLocation} &rarr;
     * {@code Identifier} in 26.1) rather than obfuscation-to-clear mappings.
     *
     * @param mojangName the old Mojang class name
     * @return the current (renamed) class name, or {@link Optional#empty()} if no rename entry
     */
    public Optional<String> getClassRename(String mojangName) {
        return Optional.ofNullable(classRenameForward.get(mojangName));
    }

    /**
     * Reverse lookup: find the old Mojang class name for a given current class name.
     *
     * @param currentName the current class name in this version
     * @return the old Mojang class name, or {@link Optional#empty()} if no rename entry
     */
    public Optional<String> getClassRenameReverse(String currentName) {
        return Optional.ofNullable(classRenameReverse.get(currentName));
    }

    /** @return the number of cross-version class renames in this table */
    public int renameCount() {
        return classRenameForward.size();
    }

    /** @return the number of mapped classes in this table */
    public int size() {
        return classForward.size();
    }

    // ── Parsing ────────────────────────────────────────────

    private static MappingTable parse(String version, Reader reader) throws IOException {
        JsonObject root;
        try {
            root = GSON.fromJson(reader, JsonObject.class);
        } catch (JsonParseException e) {
            throw new IOException("Malformed mapping table JSON for version " + version, e);
        }

        if (root == null) {
            throw new IOException("Empty mapping table JSON for version " + version);
        }

        boolean base = root.has("base") && root.get("base").getAsBoolean();
        MappingTable table = new MappingTable(version, base);

        // Parse classes block (obfuscation mappings)
        JsonObject classes = root.getAsJsonObject("classes");
        if (classes != null) {
            for (Map.Entry<String, JsonElement> classEntry : classes.entrySet()) {
                String mojangClassName = classEntry.getKey();
                JsonObject classObj = classEntry.getValue().getAsJsonObject();

                String obfClassName = classObj.has("obf")
                        ? classObj.get("obf").getAsString()
                        : mojangClassName;

                Map<String, String> methods = new HashMap<>();
                Map<String, String> fields = new HashMap<>();
                Map<String, String> methodReverse = new HashMap<>();
                Map<String, String> fieldReverse = new HashMap<>();

                // Parse methods: "mojangDesc" → "obfName"
                JsonObject methodsObj = classObj.getAsJsonObject("methods");
                if (methodsObj != null) {
                    for (Map.Entry<String, JsonElement> m : methodsObj.entrySet()) {
                        String mojangDesc = m.getKey();
                        String obfName = m.getValue().getAsString();
                        methods.put(mojangDesc, obfName);
                        methodReverse.put(obfName, mojangDesc);
                    }
                }

                // Parse fields: "mojangName" → "obfName"
                JsonObject fieldsObj = classObj.getAsJsonObject("fields");
                if (fieldsObj != null) {
                    for (Map.Entry<String, JsonElement> f : fieldsObj.entrySet()) {
                        String mojangName = f.getKey();
                        String obfName = f.getValue().getAsString();
                        fields.put(mojangName, obfName);
                        fieldReverse.put(obfName, mojangName);
                    }
                }

                ObfClassEntry entry = new ObfClassEntry(obfClassName, methods, fields,
                        methodReverse, fieldReverse);
                table.classForward.put(mojangClassName, entry);
                table.classReverse.put(obfClassName, mojangClassName);
            }
        }

        // Parse classRenames block (cross-version API renames)
        JsonObject classRenames = root.getAsJsonObject("classRenames");
        if (classRenames != null) {
            for (Map.Entry<String, JsonElement> renameEntry : classRenames.entrySet()) {
                String oldName = renameEntry.getKey();
                JsonObject renameObj = renameEntry.getValue().getAsJsonObject();
                String currentName = renameObj.has("current")
                        ? renameObj.get("current").getAsString()
                        : oldName;
                table.classRenameForward.put(oldName, currentName);
                table.classRenameReverse.put(currentName, oldName);
            }
        }

        LOGGER.info("[ZCSLIB/Mixin] Mapping table v{} loaded: {} class(es), {} rename(s), base={}",
                version, table.size(), table.renameCount(), base);
        return table;
    }

    // ── Internal data class ────────────────────────────────

    /**
     * Per-class mapping entry: obf name for the class itself,
     * plus method and field maps (Mojang &rarr; obf) and reverse indexes.
     */
    private record ObfClassEntry(
            String obfName,
            Map<String, String> methods,       // Mojang desc → obf name
            Map<String, String> fields,         // Mojang name → obf name
            Map<String, String> methodReverse,  // obf name → Mojang desc
            Map<String, String> fieldReverse    // obf name → Mojang name
    ) {}
}
