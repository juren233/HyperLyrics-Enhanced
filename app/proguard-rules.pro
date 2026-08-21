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
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaPlugin { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaHost { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaSubscription { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodResultCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorCallback { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeSource { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeReference { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderNextTrackFrame { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol { *; }

# Official Provider Packs compile against kotlin-stdlib as compileOnly and run inside an
# InMemoryDexClassLoader whose parent is the core module. Kotlin Runtime is therefore part of
# the external Pack ABI: Release R8 must not remove or rename classes that Pack bytecode calls.
-keep class kotlin.** { *; }

# Provider Packs also compile against the Lyricon Provider SDK as compileOnly. Preserve the
# provider runtime and lyric models that Pack bytecode resolves through the core class loader.
-keep class io.github.proify.lyricon.provider.** { *; }
-keep class io.github.proify.lyricon.lyric.model.** { *; }

# Lyricon registration and delivery cross process and class-loader boundaries through AIDL,
# Binder descriptors, Parcelable models and broadcast receivers. The upstream AARs currently
# ship empty consumer ProGuard rules, so preserve the Subscriber SDK and embedded Central as
# one external runtime contract instead of allowing class merging or signature optimization.
-keep class io.github.proify.lyricon.subscriber.** { *; }
-keep class io.github.proify.lyricon.central.** { *; }
