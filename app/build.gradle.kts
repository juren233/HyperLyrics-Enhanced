import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)

}

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.reader(Charsets.UTF_8).use(::load)
    }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName) ?: keystoreProperties.getProperty(propertyName)

val releaseStoreFile = signingValue("RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).none { it.isNullOrBlank() }

// CI materializes the beta/canary suffix at build time; local development keeps
// the versionName declared in this file.
val ciVersionName = providers.gradleProperty("ciVersionName").orNull

android {
    namespace = "com.juren233.hyperlyricsenhanced"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.juren233.hyperlyricsenhanced"
        minSdk = 33
        targetSdk = 37
        versionCode = 230089
        versionName = ciVersionName ?: "8.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    androidResources {
        // The app ships Chinese (default) and English strings only; library string tables for
        // the other ~80 locales are dead weight in the uncompressed resources.arsc.
        localeFilters += setOf("zh", "en")
        // The Apple Music baseline Pack is already a deflated zip (APK deflate saves ~0.3%),
        // so store it to avoid inflating ~1 MB on every Apple Music cold start.
        noCompress += "hlp"
    }

    packaging {
        dex {
            // Compress classes.dex in the APK. Uncompressed (stored) dex is the AGP
            // default for install-time read speed, but this module ships as a direct
            // APK download, where deflating dex roughly halves the artifact size.
            useLegacyPackaging = true
        }
        jniLibs {
            // libxposed target processes load DexKit from the module's absolute
            // nativeLibraryDir, so the module .so must be extracted on install.
            useLegacyPackaging = true
        }
        resources {
            // bcprov's Picnic implementation ships three precomputed LowMC tables totaling
            // about 1.2 MB. HLE only uses the Ed25519 lightweight signer, so these resources
            // are unreachable and can be excluded without changing signature verification.
            excludes += setOf(
                "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
            )
        }
    }

    val releaseSigning = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseStoreFile))
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        debug {
            releaseSigning?.let { signingConfig = it }
        }
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    afterEvaluate {
        base {
            val vName = android.defaultConfig.versionName ?: "0"
            val vCode = android.defaultConfig.versionCode ?: 0
            archivesName.set("HyperLyrics Enhanced-v${vName}-${vCode}")
        }
    }

    compileOptions {
        // miuix-nav 0.9.4 以 JVM 21 字节码发布且含 inline API，本项目必须对齐 21
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

}

dependencies {
    // --- 基本依赖 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hiddenapibypass)
    implementation(libs.bouncycastle.provider)
    implementation(libs.dexkit)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // libxposed API
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // SuperLyric API
    implementation(libs.superlyricapi)

    // Lyricon Subscriber SDK
    implementation(libs.lyricon.subscriber)
    implementation(libs.lyricon.provider)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
    testImplementation("org.json:json:20180813")

    // --- 布局兼容 ---
    implementation(libs.androidx.constraintlayout)

    // --- Compose 核心 ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.markwon.core)

    // Activity Compose
    implementation(libs.androidx.activity.compose)

    // --- MIUI X 组件库 ---
    implementation(libs.miuix.ui.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.nav.android)


    // --- 调试工具 ---
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- 网络与序列化 (在线歌词) ---
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit2.retrofit)
    implementation(libs.squareup.okhttp3)
    implementation(libs.retrofit2.kotlinx.serialization.converter)

    // --- 动画库 (YoYo) ---
    implementation(libs.daimajia.animations) { artifact { type = "aar" } }
    implementation(libs.daimajia.easing) { artifact { type = "aar" } }
}

apply(from = "fetch_contributors.gradle")
tasks.named("preBuild") {
    dependsOn("generateContributors")
}

// The Apple Music baseline Pack is shipped inside the APK so Apple Music works out of the
// box. Release builds resolve it from the signed HLE-Providers catalog (optionally pinned
// with -PappleMusicBaselineVersion=<versionCode>), verify catalog signature, pack SHA-256,
// pack signature and manifest compatibility, then embed it. A missing or invalid pack is a
// hard build failure: the APK must never ship without its out-of-the-box provider.
val appleMusicBaselinePublicKey =
    "MCowBQYDK2VwAyEAUQ05FgAS98xeNOtYppCBq1BUjEhwdxrChjwpK5sRgQU="
val appleMusicBaselineAsset =
    layout.projectDirectory.file("src/main/assets/providers/applemusic.hlp")
val appleMusicCatalogBase = providers.gradleProperty("appleMusicBaselineCatalogBase")
    .orElse("https://raw.githubusercontent.com/juren233/HLE-Providers/main/catalog/")
val appleMusicPinnedVersion = providers.gradleProperty("appleMusicBaselineVersion")
val appleMusicRefresh = providers.gradleProperty("appleMusicBaselineRefresh")
val appleMusicExplicitUrl = providers.gradleProperty("appleMusicBaselineUrl")
val appleMusicExplicitSha256 = providers.gradleProperty("appleMusicBaselineSha256")
val appleMusicCoreVersionCode = requireNotNull(android.defaultConfig.versionCode) {
    "Apple Music 基线包校验需要 versionCode"
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun ed25519Verify(payload: ByteArray, signature: ByteArray): Boolean = runCatching {
    val key = KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(appleMusicBaselinePublicKey)),
    )
    Signature.getInstance("Ed25519").run {
        initVerify(key)
        update(payload)
        verify(signature)
    }
}.getOrDefault(false)

fun fetchBytes(url: String): ByteArray {
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 60_000
    }
    return connection.getInputStream().use { it.readBytes() }
}

@Suppress("UNCHECKED_CAST")
fun jsonObject(bytes: ByteArray): Map<String, Any?> =
    groovy.json.JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)) as Map<String, Any?>

fun jsonObjects(value: Any?): List<Map<String, Any?>> =
    @Suppress("UNCHECKED_CAST")
    (value as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

fun intField(value: Any?, field: String): Int =
    (value as? Number)?.toInt() ?: error("Apple Music 基线包字段无效: $field=$value")

data class AppleMusicPackInfo(
    val versionCode: Int,
    val minCoreVersionCode: Int,
    val pluginApiVersion: Int,
)

data class AppleMusicCatalogRelease(
    val versionCode: Int,
    val minCoreVersionCode: Int,
    val assetUrl: String,
    val sha256: String,
)

fun validateAppleMusicPack(bytes: ByteArray, coreVersionCode: Int): AppleMusicPackInfo {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
        }
    }
    val manifestBytes = requireNotNull(entries["manifest.json"]) {
        "Apple Music 基线包缺少 manifest.json"
    }
    val manifest = jsonObject(manifestBytes)
    require(intField(manifest["schemaVersion"], "schemaVersion") == 1) {
        "Apple Music 基线包格式不受支持"
    }
    val pluginApiVersion = intField(manifest["pluginApiVersion"], "pluginApiVersion")
    require(pluginApiVersion == 3) {
        "Apple Music 基线包插件 API 不兼容: $pluginApiVersion"
    }
    require(manifest["pluginId"] == "applemusic") {
        "Apple Music 基线包 ID 不匹配: ${manifest["pluginId"]}"
    }
    val versionCode = intField(manifest["versionCode"], "versionCode")
    val minCoreVersionCode = intField(manifest["minCoreVersionCode"], "minCoreVersionCode")
    require(minCoreVersionCode <= coreVersionCode) {
        "Apple Music 基线包要求主程序 $minCoreVersionCode，当前为 $coreVersionCode"
    }
    require((manifest["entryClass"] as? String).orEmpty()
        .startsWith("com.juren233.hle.providers.")) {
        "Apple Music 基线包入口类不在允许命名空间"
    }
    require((manifest["targetPackages"] as? List<*>)?.contains("com.apple.android.music") == true) {
        "Apple Music 基线包不支持 com.apple.android.music"
    }

    val dexNames = entries.keys
        .filter { it == "classes.dex" || Regex("""classes([2-9][0-9]*)\.dex""").matches(it) }
        .sortedBy {
            if (it == "classes.dex") 1
            else it.removePrefix("classes").removeSuffix(".dex").toInt()
        }
    require(dexNames.isNotEmpty() && dexNames.first() == "classes.dex") {
        "Apple Music 基线包缺少 classes.dex"
    }
    require(dexNames.mapIndexed { index, name ->
        name == if (index == 0) "classes.dex" else "classes${index + 1}.dex"
    }.all { it }) {
        "Apple Music 基线包 DEX 编号不连续: $dexNames"
    }
    val dexFiles = dexNames.map { name -> requireNotNull(entries[name]) }
    require(dexFiles.sumOf { it.size } <= 48 * 1024 * 1024) {
        "Apple Music 基线包 DEX 过大"
    }
    val classesSha = (manifest["classesSha256"] as? String).orEmpty()
    require(classesSha.equals(sha256Hex(dexFiles.first()), ignoreCase = true)) {
        "Apple Music 基线包 classes.dex 摘要不匹配"
    }
    val dexSha = (manifest["dexSha256"] as? Map<*, *>)?.entries
        ?.associate { (key, value) -> key.toString() to value.toString() }
        .orEmpty()
    if (dexSha.isNotEmpty()) {
        require(dexSha.keys == dexNames.toSet()) {
            "Apple Music 基线包 DEX 摘要字段与文件不一致"
        }
        dexNames.forEach { name ->
            require(dexSha.getValue(name).equals(sha256Hex(entries.getValue(name)), true)) {
                "Apple Music 基线包 $name 摘要不匹配"
            }
        }
    }

    val signatureBytes = requireNotNull(entries["signature.ed25519"]) {
        "Apple Music 基线包缺少签名"
    }
    val payload = ByteArrayOutputStream().apply {
        write(manifestBytes)
        write(0)
        dexFiles.forEachIndexed { index, dex ->
            if (index > 0) write(0)
            write(dex)
        }
    }.toByteArray()
    require(ed25519Verify(payload, signatureBytes)) {
        "Apple Music 基线包签名无效"
    }
    return AppleMusicPackInfo(versionCode, minCoreVersionCode, pluginApiVersion)
}

fun pinnedAppleMusicVersion(): Int? =
    appleMusicPinnedVersion.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        value.toIntOrNull()
            ?: error("appleMusicBaselineVersion 必须是 versionCode: $value")
    }

fun resolveAppleMusicCatalogRelease(): AppleMusicCatalogRelease {
    val pinned = pinnedAppleMusicVersion()
    val explicitUrl = appleMusicExplicitUrl.orNull?.trim()
    if (!explicitUrl.isNullOrEmpty()) {
        val explicitSha = requireNotNull(appleMusicExplicitSha256.orNull?.trim()) {
            "配置 appleMusicBaselineUrl 时必须同时配置 appleMusicBaselineSha256"
        }
        return AppleMusicCatalogRelease(0, 0, explicitUrl, explicitSha)
    }

    val base = appleMusicCatalogBase.get().let { if (it.endsWith("/")) it else "$it/" }
    val catalogBytes = fetchBytes("${base}catalog.json")
    val signatureBytes = Base64.getDecoder().decode(
        fetchBytes("${base}catalog.sig").toString(Charsets.UTF_8).trim(),
    )
    check(ed25519Verify(catalogBytes, signatureBytes)) { "Provider 目录签名无效: $base" }
    val catalog = jsonObject(catalogBytes)
    val provider = jsonObjects(catalog["providers"])
        .firstOrNull { it["id"] == "applemusic" }
        ?: error("Provider 目录中没有 applemusic")
    val releases = jsonObjects(provider["releases"]).map { release ->
        AppleMusicCatalogRelease(
            versionCode = intField(release["versionCode"], "versionCode"),
            minCoreVersionCode = intField(release["minCoreVersionCode"], "minCoreVersionCode"),
            assetUrl = requireNotNull(release["assetUrl"] as? String) {
                "applemusic release 缺少 assetUrl"
            },
            sha256 = requireNotNull(release["sha256"] as? String) {
                "applemusic release 缺少 sha256"
            },
        )
    }
    return if (pinned != null) {
        releases.firstOrNull { it.versionCode == pinned }
            ?: error("Provider 目录中没有 applemusic versionCode=$pinned")
    } else {
        releases.maxByOrNull { it.versionCode }
            ?: error("Provider 目录中 applemusic 没有发行版")
    }
}

val prepareAppleMusicBaselinePack =
    tasks.register("prepareAppleMusicBaselinePack") {
        val outputFile = appleMusicBaselineAsset
        inputs.property("pinnedVersion", appleMusicPinnedVersion.orElse(""))
        inputs.property("refresh", appleMusicRefresh.orElse("false"))
        inputs.property("catalogBase", appleMusicCatalogBase)
        inputs.property("explicitUrl", appleMusicExplicitUrl.orElse(""))
        inputs.property("explicitSha256", appleMusicExplicitSha256.orElse(""))
        outputs.file(outputFile)
        doLast {
            val target = outputFile.asFile
            val pinned = pinnedAppleMusicVersion()
            val refresh = appleMusicRefresh.orNull?.toBoolean() == true
            val existing = if (target.isFile) {
                runCatching { validateAppleMusicPack(target.readBytes(), appleMusicCoreVersionCode) }
                    .onFailure {
                        logger.warn("本地 Apple Music 基线包无效，将从目录重新获取: ${it.message}")
                    }
                    .getOrNull()
            } else {
                null
            }
            if (existing != null && (pinned == null || existing.versionCode == pinned) && !refresh) {
                logger.lifecycle(
                    "Apple Music 基线包已就绪: version=${existing.versionCode} " +
                        "minCore=${existing.minCoreVersionCode}",
                )
                return@doLast
            }

            val release = resolveAppleMusicCatalogRelease()
            val bytes = fetchBytes(release.assetUrl)
            check(sha256Hex(bytes).equals(release.sha256, ignoreCase = true)) {
                "Apple Music 基线包摘要不匹配: expected=${release.sha256} " +
                    "actual=${sha256Hex(bytes)}"
            }
            val info = validateAppleMusicPack(bytes, appleMusicCoreVersionCode)
            if (release.versionCode > 0) {
                check(info.versionCode == release.versionCode) {
                    "Apple Music 基线包版本与目录不一致: " +
                        "catalog=${release.versionCode} pack=${info.versionCode}"
                }
            }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
            logger.lifecycle(
                "Apple Music 基线包已更新: version=${info.versionCode} " +
                    "minCore=${info.minCoreVersionCode} source=${release.assetUrl}",
            )
        }
    }

tasks.named("preBuild") {
    dependsOn(prepareAppleMusicBaselinePack)
}
