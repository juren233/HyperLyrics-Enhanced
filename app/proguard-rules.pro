-keep class com.juren233.hyperlyricsenhanced.root.** { *; }
-keep class com.juren233.hyperlyricsenhanced.common.RootConstants { *; }
-keep class com.juren233.hyperlyricsenhanced.common.ServiceConstants { *; }
-keep class com.juren233.hyperlyricsenhanced.common.UIConstants { *; }

# 保护 libxposed 接口
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }

# 保护 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# --- Compose 相关规则 (防止误删) ---
-keepattributes *Annotation*, Signature, InnerClasses
-dontwarn androidx.compose.**

# --- Serialization 和在线网络模型防止混淆 ---
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep class com.juren233.hyperlyricsenhanced.online.** { *; }

# --- 歌词数据模型（Parcelable + Serializable）---
-keep class com.juren233.hyperlyricsenhanced.lyric.model.** { *; }

# Runtime hook target verified from provider-0.1.70.aar. The reconnect control-frame bridge
# resolves this exact binary class and its method names, so R8 must not rename or inline it.
-keep class io.github.proify.lyricon.provider.CachedRemotePlayer { *; }

# --- Shizuku User Service ---
-keep class com.juren233.hyperlyricsenhanced.service.utils.shizuku.PrivilegedServiceImpl { *; }

# --- SuperLyric API ---
-keep class com.hchen.superlyricapi.* { *; }
-dontwarn android.os.ServiceManager

# Provider Pack Ed25519 verifier (Bouncy Castle lightweight primitives)
-keep class org.bouncycastle.crypto.** { *; }

# Stable ABI used by independently compiled official Provider Packs.
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodResultCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorCallback { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderNextTrackFrame { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol { *; }
