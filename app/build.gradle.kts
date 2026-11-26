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
        versionCode = 250093
        versionName = ciVersionName ?: "8.5.0-canary"

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

    testOptions {
        // 只在本地渲染界面预览时打开（-PhleTestResources），平时的单测和 CI 不受影响。
        unitTests.isIncludeAndroidResources = providers.gradleProperty("hleTestResources").isPresent
        unitTests.all {
            // Robolectric 在 JDK 17+ 上经 jdk.internal.access.SharedSecrets 读写 FileDescriptor 内部字段，
            // JDK 21 不导出这些内部包时测试在初始化阶段就失败（IllegalAccessException）。
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
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
    // 只进单元测试类路径，不打进 APK：用真实 Android 动画代码复现岛歌词视图的动画状态。
    testImplementation(libs.robolectric)
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
    implementation(libs.squareup.okhttp3)

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

// SystemUI hot reload hands Apple Music a process-lifetime relay Binder instead of a Binder whose
// class belongs to one module generation (see docs/knowledge-base/systemui-hot-reload.md). The relay
// must be defined by its own ClassLoader, so it is compiled outside the app dex into a tiny dex that
// is embedded as a constant and loaded with InMemoryDexClassLoader at runtime. Debug and Release
// embed the same bytes; R8 never sees the relay class.
abstract class GenerateGenerationRelayDex : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val relaySources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidJar: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val d8Executable: RegularFileProperty

    @get:Input
    abstract val minApi: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val work = workDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val classesDir = File(work, "classes").apply { mkdirs() }
        val dexDir = File(work, "dex").apply { mkdirs() }
        val sources = relaySources.get().asFile.walkTopDown().filter { it.extension == "java" }.toList()
        check(sources.isNotEmpty()) { "relay sources missing" }
        val compiler = checkNotNull(javax.tools.ToolProvider.getSystemJavaCompiler()) {
            "Gradle must run on a JDK to compile the generation relay"
        }
        val args = listOf(
            "--release", "11",
            "-classpath", androidJar.files.joinToString(File.pathSeparator),
            "-d", classesDir.absolutePath,
        ) + sources.map { it.absolutePath }
        check(compiler.run(null, null, null, *args.toTypedArray()) == 0) { "relay javac failed" }
        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList()
        val command = listOf(
            d8Executable.get().asFile.absolutePath,
            "--release",
            "--min-api", minApi.get().toString(),
            "--output", dexDir.absolutePath,
        ) + androidJar.files.flatMap { listOf("--lib", it.absolutePath) } + classFiles.map { it.absolutePath }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "relay d8 failed:\n$log" }
        val dex = File(dexDir, "classes.dex").readBytes()
        val encoded = Base64.getEncoder().encodeToString(dex)
        val out = File(outputDir.get().asFile, "com/juren233/hyperlyricsenhanced/root/reload").apply {
            deleteRecursively(); mkdirs()
        }
        File(out, "GenerationRelayDex.java").writeText(
            """
            |/* Generated by :app:generateGenerationRelayDex from src/relay/java. Do not edit. */
            |package com.juren233.hyperlyricsenhanced.root.reload;
            |
            |final class GenerationRelayDex {
            |    static final String CLASS_NAME = "com.juren233.hyperlyricsenhanced.relay.GenerationRelayBinder";
            |    static final String DEX_BASE64 = "$encoded";
            |
            |    private GenerationRelayDex() {}
            |}
            |""".trimMargin(),
        )
    }
}

val generateGenerationRelayDex = tasks.register<GenerateGenerationRelayDex>("generateGenerationRelayDex") {
    relaySources.set(layout.projectDirectory.dir("src/relay/java"))
    androidJar.from(androidComponents.sdkComponents.bootClasspath)
    d8Executable.set(
        androidComponents.sdkComponents.sdkDirectory.map {
            it.file("build-tools/${android.buildToolsVersion}/d8")
        },
    )
    minApi.set(checkNotNull(android.defaultConfig.minSdk))
    outputDir.set(layout.buildDirectory.dir("generated/source/generationRelay"))
    workDir.set(layout.buildDirectory.dir("intermediates/generationRelay"))
}

// The Lyricon subscriber and provider libraries are bundled into the module, so in SystemUI their
// `Dispatchers.Default` is this module generation's kotlinx-coroutines scheduler, whose core workers
// never exit and keep a retired generation alive (250043 device heap). Their calls are rewritten at
// build time to ModuleDispatchers.Default (user decision 2026-11-23). A target that is missing or no
// longer makes the call fails the build: the transform at the class, the artifact check per APK.
object LyriconDispatcherRewriteSpec {
    const val DISPATCHERS = "kotlinx/coroutines/Dispatchers"
    const val MODULE_DISPATCHERS = "com/juren233/hyperlyricsenhanced/common/coroutines/ModuleDispatchers"
    const val GET_DEFAULT = "getDefault"
    const val DISPATCHER_GETTER = "()Lkotlinx/coroutines/CoroutineDispatcher;"
    val TARGETS = setOf(
        "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl",
        "io.github.proify.lyricon.subscriber.ActivePlayerListenerDispatcher",
        "io.github.proify.lyricon.provider.impl.ProviderV27Impl",
    )
}

abstract class LyriconDispatcherRewrite :
    com.android.build.api.instrumentation.AsmClassVisitorFactory<com.android.build.api.instrumentation.InstrumentationParameters.None> {
    override fun isInstrumentable(classData: com.android.build.api.instrumentation.ClassData): Boolean =
        classData.className in LyriconDispatcherRewriteSpec.TARGETS

    override fun createClassVisitor(
        classContext: com.android.build.api.instrumentation.ClassContext,
        nextClassVisitor: org.objectweb.asm.ClassVisitor,
    ): org.objectweb.asm.ClassVisitor = object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, nextClassVisitor) {
        var rewritten = 0

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): org.objectweb.asm.MethodVisitor {
            val next = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, next) {
                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                    val spec = LyriconDispatcherRewriteSpec
                    if (opcode == org.objectweb.asm.Opcodes.INVOKESTATIC && owner == spec.DISPATCHERS &&
                        name == spec.GET_DEFAULT && descriptor == spec.DISPATCHER_GETTER
                    ) {
                        rewritten++
                        super.visitMethodInsn(opcode, spec.MODULE_DISPATCHERS, name, descriptor, false)
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }

        override fun visitEnd() {
            if (rewritten == 0) {
                throw GradleException(
                    "Lyricon dispatcher rewrite: ${classContext.currentClassData.className} no longer calls " +
                        "Dispatchers.getDefault(); re-audit the library (docs/knowledge-base/systemui-hot-reload.md)",
                )
            }
            super.visitEnd()
        }
    }
}

/** Checks one packaged APK (after R8 for Release) for the rewrite, with the SDK's dexdump. */
abstract class VerifyLyriconDispatcherRewrite : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dexdumpExecutable: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val spec = LyriconDispatcherRewriteSpec
        val apks = apkDirectory.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.size == 1) { "expected one APK in ${apkDirectory.get().asFile}, found ${apks.map { it.name }}" }
        val work = File(temporaryDir, "dex").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(apks.single().inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.filter { Regex("classes\\d*\\.dex").matches(it.name) }.forEach { entry ->
                File(work, entry.name).outputStream().use { zip.copyTo(it) }
            }
        }
        val methodHeader = Regex("""^\s+#\d+\s+: \(in (L[^;]+;)\)""")
        val globalCall = Regex("""L${spec.DISPATCHERS};\.(getDefault|getIO):""")
        val moduleCall = "L${spec.MODULE_DISPATCHERS};.${spec.GET_DEFAULT}:${spec.DISPATCHER_GETTER}"
        val targets = spec.TARGETS.associateBy { "L" + it.replace('.', '/') + ";" }
        val present = mutableSetOf<String>()
        val rewritten = mutableMapOf<String, Int>()
        val offenders = mutableListOf<String>()
        work.listFiles().orEmpty().sortedBy { it.name }.forEach { dex ->
            val process = ProcessBuilder(dexdumpExecutable.get().asFile.absolutePath, "-d", dex.absolutePath)
                .redirectErrorStream(true).start()
            var owner: String? = null
            process.inputStream.bufferedReader().forEachLine { line ->
                methodHeader.find(line)?.let { owner = it.groupValues[1] }
                val current = owner ?: return@forEachLine
                if (current in targets) present += current
                if (current.startsWith("Lio/github/proify/lyricon/") && globalCall.containsMatchIn(line)) {
                    offenders += "$current: ${line.trim()}"
                }
                if (current in targets && moduleCall in line) rewritten.merge(current, 1, Int::plus)
            }
            check(process.waitFor() == 0) { "dexdump failed on ${dex.name}" }
        }
        val missing = targets.keys - present
        val notRewritten = targets.keys - rewritten.keys
        check(missing.isEmpty()) { "Lyricon dispatcher rewrite targets missing from ${apks.single().name}: $missing" }
        check(notRewritten.isEmpty()) { "Lyricon classes do not call ModuleDispatchers.Default in ${apks.single().name}: $notRewritten" }
        check(offenders.isEmpty()) {
            "Lyricon classes still use the global dispatchers in ${apks.single().name}:\n" + offenders.joinToString("\n")
        }
        report.get().asFile.writeText(
            apks.single().name + "\n" + rewritten.entries.joinToString("\n") { (cls, n) -> "$cls ModuleDispatchers.getDefault x$n" } + "\n",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateGenerationRelayDex,
            GenerateGenerationRelayDex::outputDir,
        )
        variant.instrumentation.transformClassesWith(
            LyriconDispatcherRewrite::class.java,
            com.android.build.api.instrumentation.InstrumentationScope.ALL,
        ) {}
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyLyriconDispatcherRewrite>("verify${cap}LyriconDispatcherRewrite") {
            apkDirectory.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            dexdumpExecutable.set(
                androidComponents.sdkComponents.sdkDirectory.map {
                    it.file("build-tools/${android.buildToolsVersion}/dexdump")
                },
            )
            report.set(layout.buildDirectory.file("reports/lyriconDispatcherRewrite/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(verify) }
        // Unit tests exercise the relay class directly on the JVM.
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]
            ?.sources?.java?.addStaticSourceDirectory("src/relay/java")
    }
}
