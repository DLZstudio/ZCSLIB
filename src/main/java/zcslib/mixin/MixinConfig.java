package zcslib.mixin;

import java.util.List;

/**
 * Merged Mixin configuration written to {@code mixins.zcslib.json}.
 *
 * <p>This is the single config fed to {@code Mixins.addConfiguration()}
 * at mod construction time. It aggregates Mixin classes from all plugins
 * plus ZCSLIB's own built-in Mixins.
 *
 * @param required           whether the config is mandatory (always {@code true})
 * @param mixinPackage       base package for Mixin classes
 * @param compatibilityLevel Java language level (always {@code "JAVA_21"})
 * @param mixins             list of fully-qualified Mixin class names
 * @param refmap             relative path to the merged refmap JSON
 */
record MixinConfig(
        boolean required,
        String mixinPackage,
        String compatibilityLevel,
        List<String> mixins,
        String refmap
) {}
