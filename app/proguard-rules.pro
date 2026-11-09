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

# Provider Pack verification calls Bouncy Castle Ed25519 through ordinary static references.
# Let R8 retain only the reachable Ed25519 implementation instead of the whole crypto package.

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
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderInterceptor { *; }
-keep interface com.juren233.hyperlyricsenhanced.provider.OfficialProviderHookHandle { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeSource { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeReference { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodAnnotationConstraint { *; }
-keep class com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQueryBuilder { *; }
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

# The Apple Music Pack also compiles against kotlinx.coroutines and kotlinx.serialization
# as compileOnly and resolves them from the core class loader at runtime. Keep only the
# classes the Pack references: keeping all of kotlinx.coroutines disables R8's direct
# Main-dispatcher instantiation, and LSPosed module class loaders cannot read the
# META-INF/services entry, so Dispatchers.Main would fail inside target processes.
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.CoroutineStart { *; }
-keep class kotlinx.coroutines.DelayKt { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.Job$DefaultImpls { *; }
-keep class kotlinx.coroutines.SupervisorKt { *; }
-keep class kotlinx.serialization.** { *; }

# OfficialProviderHost.openDexKit returns DexKitBridge; Packs compile against the same DexKit
# version as compileOnly and resolve these classes through the core class loader.
-keep class org.luckypray.dexkit.** { *; }

# Apple Music Provider is distributed as a Pack. Everything below is the narrow core surface
# that Pack bytecode resolves through the core class loader; rename or removal here is a
# runtime NoSuchMethodError for already published Packs.
-keep class com.juren233.hyperlyricsenhanced.contract.** { *; }
-keep class com.juren233.hyperlyricsenhanced.lyric.model.** { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy { *; }
# QQ 音乐插件（Pack）与核心共用的按歌名查曲库文本规则，插件字节码经核心类加载器解析。
-keep class com.juren233.hyperlyricsenhanced.common.lyric.SongMetadataQueryPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy$* { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStat { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStatsCodec { *; }
-keep class com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy { *; }
-keep class com.juren233.hyperlyricsenhanced.common.bridge.** { *; }
-keep class com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge { *; }
-keep class com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge$* { *; }
-keep class com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver { *; }
-keep class com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver$* { *; }

# Room resolves generated *_Impl databases reflectively; room-runtime only keeps the class,
# so R8 may otherwise drop the no-arg constructor WorkManager instantiates at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Lyricon registration and delivery cross process and class-loader boundaries through AIDL,
# Binder descriptors, Parcelable models and broadcast receivers. The upstream AARs currently
# ship empty consumer ProGuard rules, so preserve the Subscriber SDK and embedded Central as
# one external runtime contract instead of allowing class merging or signature optimization.
-keep class io.github.proify.lyricon.subscriber.** { *; }
-keep class io.github.proify.lyricon.central.** { *; }
