# ============================================================================
# Carlink Native — ProGuard / R8 Rules
# ============================================================================
# Referenced by: app/build.gradle.kts  (release { proguardFiles(...) })
# These rules are required when isMinifyEnabled is set to true.
#
# WHY: The CPC200-CCPA protocol layer and adapter config use enum .name
# values as protocol strings, config keys, and structured identifiers.
# R8 obfuscation renames enum constants (e.g. CARPLAY → "a"), silently
# breaking protocol logic with NO compile-time warning. These rules
# prevent that. Even enums not currently using .name are kept because
# future features that reference them by name would break silently if
# a developer doesn't think to update this file.
# ============================================================================

# ----------------------------------------------------------------------------
# 1. Protocol enums — CPC200-CCPA adapter communication
# ----------------------------------------------------------------------------
# All enums in the protocol package define wire-level identifiers for the
# Carlinkit adapter. Their names must remain stable for logging, debugging,
# diagnostics, and any future name-based protocol usage.

# Command IDs (H→A, A→H, P→A→H bidirectional commands)
-keepclassmembers class com.carlink.protocol.CommandMapping {
    <fields>;
}

# Message type IDs (header type field)
-keepclassmembers class com.carlink.protocol.MessageType {
    <fields>;
}

# Audio stream control commands
-keepclassmembers class com.carlink.protocol.AudioCommand {
    <fields>;
}

# Connected phone/device type identifiers
-keepclassmembers class com.carlink.protocol.PhoneType {
    <fields>;
}

# Media metadata type identifiers
-keepclassmembers class com.carlink.protocol.MediaType {
    <fields>;
}

# Touch event action types
-keepclassmembers class com.carlink.protocol.MultiTouchAction {
    <fields>;
}

# Adapter filesystem paths for config files
-keepclassmembers class com.carlink.protocol.FileAddress {
    <fields>;
}

# ----------------------------------------------------------------------------
# 2. Adapter configuration enums — sent as protocol commands/strings
# ----------------------------------------------------------------------------
# InitMode.name is passed directly to MessageSerializer.generateInitSequence()
# which compares against literal strings "MINIMAL_ONLY", "MINIMAL_PLUS_CHANGES".
# This is the most critical rule — obfuscation here silently breaks init.
-keepclassmembers class com.carlink.ui.settings.AdapterConfigPreference$InitMode {
    <fields>;
}

# Audio source, mic source, WiFi band, call quality — these map to adapter
# command codes. Names used in logging and config persistence.
-keepclassmembers class com.carlink.ui.settings.AudioSourceConfig {
    <fields>;
}
-keepclassmembers class com.carlink.ui.settings.MicSourceConfig {
    <fields>;
}
-keepclassmembers class com.carlink.ui.settings.WiFiBandConfig {
    <fields>;
}
-keepclassmembers class com.carlink.ui.settings.CallQualityConfig {
    <fields>;
}
-keepclassmembers class com.carlink.ui.settings.MediaDelayConfig {
    <fields>;
}

# ----------------------------------------------------------------------------
# 3. App state and logging enums
# ----------------------------------------------------------------------------
# CarlinkManager.State.name is serialized in getPerformanceStats().
-keepclassmembers class com.carlink.CarlinkManager$State {
    <fields>;
}

# Logger.Level.name.first() extracts V/D/I/W/E for on-device log file
# format (FileLogManager.kt:161). Wrong characters break log parsing.
-keepclassmembers class com.carlink.logging.Logger$Level {
    <fields>;
}

# Logger.LogLevel — used alongside Logger.Level for preset filtering.
-keepclassmembers class com.carlink.logging.Logger$LogLevel {
    <fields>;
}

# ----------------------------------------------------------------------------
# 5. General enum safety net
# ----------------------------------------------------------------------------
# Standard AGP rule — keep valueOf/values on all enums so Kotlin/Java
# reflection and serialization continue to work.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# 6. Material Icons Extended — tree-shaking note
# ----------------------------------------------------------------------------
# androidx.compose.material:material-icons-extended ships ~12 MB of icon
# classes. R8 tree-shaking removes unused icons automatically. No keep
# rule is needed here; this comment is a reminder that enabling minification
# is the primary way to reduce APK size from this dependency.

# ----------------------------------------------------------------------------
# 7. Kotlin Serialization (future-proofing)
# ----------------------------------------------------------------------------
# If kotlinx-serialization is added later, uncomment:
# -keepattributes *Annotation*, InnerClasses
# -keep,includedescriptorclasses class com.carlink.**$$serializer { *; }
# -keepclassmembers class com.carlink.** {
#     *** Companion;
# }
# -keepclasseswithmembers class com.carlink.** {
#     kotlinx.serialization.KSerializer serializer(...);
# }

# ----------------------------------------------------------------------------
# 8. AndroidX / Compose / Media — handled automatically
# ----------------------------------------------------------------------------
# AndroidX libraries ship their own consumer ProGuard rules (AAR META-INF).
# The Kotlin Compose compiler plugin keeps @Composable functions.
# AGP keeps all manifest-declared components (Activity, Service, Receiver).
# No additional rules needed for these.
