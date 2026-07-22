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

# --- Shizuku User Service ---
-keep class com.juren233.hyperlyricsenhanced.service.utils.shizuku.PrivilegedServiceImpl { *; }

# --- SuperLyric API ---
-keep class com.hchen.superlyricapi.* { *; }
-dontwarn android.os.ServiceManager