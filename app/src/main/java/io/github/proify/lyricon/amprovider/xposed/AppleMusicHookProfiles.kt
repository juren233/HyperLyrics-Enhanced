/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Apple Music 安装包版本，用于选择对应的混淆 Hook 档案。 */
internal data class AppleMusicVersion(
    val versionName: String?,
    val versionCode: Long?,
) {
    val displayName: String
        get() = "${versionName ?: "unknown"} (${versionCode ?: "unknown"})"
}

/**
 * 所有已经确认会随 Apple Music 混淆版本变化的 Hook 入口。
 *
 * 新版 Apple Music 适配应优先只修改本文件中的版本档案；业务 Hook 不应再直接写死这些类名。
 */
internal enum class AppleMusicHookPoint {
    MEDIA_API_LOCALIZATION,
    CONTENT_HTTP_LOCALIZATION,
    EXO_MEDIA_PLAYER,
    EXO_AUDIO_SESSION_ID,
    LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
    LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
    DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID,
    DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT,
    DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION,
    DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER,
    DEBUG_ATMOS_SV_AUDIO_PERIOD_ID,
    DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED,
    DEBUG_ATMOS_SV_AUDIO_SESSION,
    DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER,
    LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
    LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
    LYRICS_NETWORK_REQUEST,
    LYRICS_COOKIE_JAR,
    EPOXY_FINAL_BIND,
    LYRICS_SOURCE_MENU_CLICK_LISTENER,
    LYRICS_WORD_RENDER_ADAPTER,
    LYRICS_RECYCLER_ADAPTER,
    LYRICS_TRANSLATION_PREFERENCE,
    LYRICS_PRONUNCIATION_PREFERENCE,
    LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
    LYRICS_PREFERRED_LANGUAGES_REQUEST,
    LYRICS_VIEW_MODEL_LOAD,
    LYRICS_VIEW_MODEL_BUILD,
    LYRICS_RESULT_PRESENTATION,
    LYRICS_NATIVE_PRESENTATION,
    LYRICS_UI_ON_CREATE_VIEW,
    LYRICS_UI_ON_RESUME,
    LYRICS_UI_ON_DESTROY_VIEW,
    LYRICS_WORD_VECTOR_CLASS,
    LYRICS_TTML_PARSER,
    LYRICS_AVAILABILITY_HAS_LYRICS,
    LYRICS_AVAILABILITY_TIME_SYNCED,
    PLAYER_LYRICS_AVAILABILITY_CALCULATOR,
    PLAYER_SONG_BINDING_EXECUTE,
    APPLE_CUSTOM_TEXT_VIEW,
    LYRICS_GRADIENT_MASK_UPDATE,
    COMPOSE_TEXT_LAYOUT,
    APPLE_TEXT_STYLE_UTILS,
    IN_APP_ACTION_SHEET_BINDING,
    IN_APP_GLOBAL_METADATA_DISPATCHER,
    IN_APP_NOW_PLAYING_METADATA_LISTENER,
    IN_APP_QUEUE_UPDATE,
    IN_APP_HISTORY_UPDATE,
    IN_APP_QUEUE_ADAPTER_SUBMIT,
    IN_APP_QUEUE_ADAPTER_BIND,
    CONTENT_ITEM_METADATA_CLASSES,
    RECENTLY_SEARCHED_CONTROLLER,
    RECENTLY_SEARCHED_MODEL_BOUND,
    RECENTLY_SEARCHED_MEDIA_ENTITY,
    APPLE_MAIN_CONTENT_ACTIVITY,
    APPLE_SHARED_PREFERENCES_CLASS,
    APPLE_SONG_MODEL_CLASS,
    APPLE_PLAYER_UTIL_CLASS,
    PLAYER_LYRICS_VIEW_MODEL_CLASS,
    IN_APP_CONTAINER_ARTIST_CLASS,
    IN_APP_CONTAINER_ALBUM_CLASS,
    MEDIA_API_REPOSITORY_HOLDER_CLASS,
    COMPOSE_NEVER_EQUAL_POLICY,
    LIBRARY_COMPOSE_VIEW_MODEL_GETTER,
    LIBRARY_EPOXY_BUILD,
    LIBRARY_COMPOSE_CONTENT,
    COMPOSE_OBSERVE_AS_STATE,
    LIBRARY_ENTITY_CLASSES,
    DATA_BINDING_RUNTIME_CLASSES,
    COLLECTION_SURFACE_CLASSES,
    ARTIST_SURFACE_CLASSES,
    LISTEN_NOW_MODEL_BUILDER,
    LISTEN_NOW_BOUND_LISTENER,
    LISTEN_NOW_MODEL,
    LISTEN_NOW_ARTWORK_RESOLVER,
    LISTEN_NOW_DELEGATING_ITEM,
    LISTEN_NOW_CUSTOM_IMAGE_VIEW,
    LISTEN_NOW_MEDIA_ENTITY,
    LISTEN_NOW_COLLECTION_ITEM_VIEW,
}

internal enum class AppleMusicRuntimeMember {
    CONTENT_HTTP_CHAIN_REQUEST_FIELD,
    CONTENT_HTTP_REQUEST_URL_FIELD,
    CONTENT_HTTP_REQUEST_HEADERS_FIELD,
    CONTENT_HTTP_RESPONSE_STATUS_FIELD,
    CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD,
    CONTENT_HTTP_HEADERS_GET_METHOD,
    EXO_SEEK_METHOD,
    EXO_PLAY_METHOD,
    EXO_PAUSE_METHOD,
    EXO_STOP_METHOD,
    EXO_RELEASE_METHOD,
    EXO_CURRENT_POSITION_METHOD,
    DEBUG_FORMAT_HOLDER_FORMAT_FIELD,
    DEBUG_FORMAT_CODECS_FIELD,
    DEBUG_FORMAT_SAMPLE_MIME_TYPE_FIELD,
    DEBUG_FORMAT_LOUDNESS_FIELD,
    DEBUG_FORMAT_CHANNEL_COUNT_FIELD,
    DEBUG_FORMAT_SAMPLE_RATE_FIELD,
    DEBUG_FORMAT_BITRATE_FIELD,
    PLAYBACK_PLAYER_CURRENT_ITEM_METHOD,
    PLAYBACK_QUEUE_ITEM_ITEM_METHOD,
    PLAYBACK_QUEUE_ITEM_ID_METHOD,
    PLAYBACK_MEDIA_ITEM_TITLE_METHOD,
    PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD,
    PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD,
    PLAYBACK_MEDIA_ITEM_DURATION_METHOD,
    PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD,
    PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD,
    APPLE_SONG_SET_ID_METHOD,
    APPLE_SONG_SET_QUEUE_ID_METHOD,
    APPLE_SONG_SET_HAS_LYRICS_METHOD,
    APPLE_PLAYER_UTIL_CONTAINER_METHOD,
    APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD,
    CONTENT_HTTP_RESPONSE_REQUEST_FIELD,
    CONTENT_HTTP_RESPONSE_HEADERS_FIELD,
    CONTENT_HTTP_HEADERS_VALUES_FIELD,
    LYRICS_COOKIE_NAME_FIELD,
    LYRICS_COOKIE_VALUE_FIELD,
    LYRICS_SOURCE_MENU_FRAGMENT_FIELD,
    LYRICS_SOURCE_MENU_FRAGMENT_CLASS,
    LYRICS_NATIVE_LINE_TEXT_METHOD,
    LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
    LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD,
    LYRICS_NATIVE_WORDS_METHOD,
    LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
    LYRICS_NATIVE_SET_TRANSLATION_METHOD,
    LYRICS_NATIVE_HAS_TRANSLATION_METHOD,
    LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
    LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD,
    LYRICS_NATIVE_POINTER_GET_METHOD,
    LYRICS_NATIVE_VECTOR_GET_METHOD,
    LYRICS_NATIVE_VECTOR_SIZE_METHOD,
    LYRICS_NATIVE_POINTER_ADDRESS_METHOD,
    LYRICS_NATIVE_SONG_SECTIONS_METHOD,
    LYRICS_NATIVE_SECTION_LINES_METHOD,
    LYRICS_NATIVE_BEGIN_METHOD,
    LYRICS_NATIVE_END_METHOD,
    LYRICS_NATIVE_DURATION_METHOD,
    LYRICS_NATIVE_WORD_ID_METHOD,
    LYRICS_NATIVE_WHITESPACE_METHOD,
    LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
    LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
    LYRICS_NATIVE_SET_ADAM_ID_METHOD,
    LYRICS_NATIVE_SET_QUEUE_ID_METHOD,
    LYRICS_NATIVE_SONG_QUEUE_ID_METHOD,
    LYRICS_NATIVE_SONG_AGENTS_METHOD,
    LYRICS_NATIVE_AGENT_METHOD,
    LYRICS_NATIVE_AGENT_NAME_TYPES_METHOD,
    LYRICS_NATIVE_AGENT_TYPE_METHOD,
    LYRICS_NATIVE_AGENT_ID_METHOD,
    LYRICS_SONG_ADAM_ID_METHOD,
    LYRICS_SONG_ID_METHOD,
    LYRICS_SONG_QUEUE_ID_METHOD,
    LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
    LYRICS_VIEW_MODEL_RESULT_GETTER,
    LYRICS_UI_RECYCLER_VIEW_METHOD,
    LYRICS_UI_ROOT_VIEW_GETTER,
    LYRICS_UI_BINDING_FIELD,
    LYRICS_UI_BINDING_RECYCLER_FIELD,
    LYRICS_UI_ADAPTER_FIELD,
    LYRICS_UI_VIEW_MODEL_FIELD,
    LYRICS_UI_LOADING_PROGRESS_RESOURCE_NAME,
    LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD,
    LYRICS_ADAPTER_LYRICS_METHOD,
    LYRICS_ADAPTER_LINE_COUNT_METHOD,
    LYRICS_ADAPTER_LINE_AT_METHOD,
    LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD,
    LYRICS_ADAPTER_ITEM_COUNT_METHOD,
    LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD,
    LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
    LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
    LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
    LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER,
    LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER,
    LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER,
    LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER,
    PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD,
    PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD,
    PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD,
    PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD,
    LYRICS_WORD_VECTOR_CLASS_NAME,
    LYRICS_GRADIENT_LAYOUT_CLASS_NAME,
    LYRICS_GRADIENT_MASK_START_CHILD_FIELD,
    LYRICS_GRADIENT_MASK_END_CHILD_FIELD,
    LYRICS_GRADIENT_MASK_POSITIONS_FIELD,
    LYRICS_GRADIENT_MASK_FRACTION_FIELD,
    QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD,
    QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD,
    QUEUE_ENTRY_ITEM_FIELD,
    QUEUE_ITEM_METADATA_FIELD,
    QUEUE_ITEM_ID_FIELD,
    QUEUE_HISTORY_ENTRY_CLASS_NAME,
    MEDIA3_METADATA_BUNDLE_FIELD,
    MEDIA3_METADATA_TITLE_FIELD,
    MEDIA3_METADATA_ARTIST_FIELD,
    CONTENT_ITEM_ROLE,
    LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD,
    LIBRARY_COMPOSE_STATE_POLICY_FIELD,
    LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD,
    LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD,
    LIBRARY_ENTITY_ROLE,
    LIBRARY_ENTITY_KIND,
    DATA_BINDING_RUNTIME_ROLE,
    DATA_BINDING_REGISTRATION_METHOD,
    DATA_BINDING_INVALIDATE_METHOD,
    DATA_BINDING_EXECUTE_METHOD,
    DATA_BINDING_SET_VARIABLE_METHOD,
    DATA_BINDING_TITLE_VARIABLE_FIELD,
    DATA_BINDING_SUBTITLE_VARIABLE_FIELD,
    COLLECTION_RUNTIME_ROLE,
    COLLECTION_ALBUM_HEADER_BUILD_METHOD,
    COLLECTION_PLAYLIST_BUILD_ITEM_METHOD,
    COLLECTION_CONTROLLER_ATTACH_METHOD,
    COLLECTION_CONTROLLER_DETACH_METHOD,
    COLLECTION_CONTROLLER_SET_DATA_METHOD,
    COLLECTION_CONTROLLER_FORCE_BUILD_METHOD,
    COLLECTION_PLAYLIST_TITLE_FIELD,
    COLLECTION_PLAYLIST_SUBTITLE_FIELD,
    COLLECTION_ENTITY_EXPLICIT_METHOD,
    APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD,
    EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD,
    ARTIST_RUNTIME_ROLE,
    ARTIST_TOP_SONG_BUILD_METHOD,
    ARTIST_PROFILE_BUILD_METHOD,
    ARTIST_MODEL_BIND_METHOD,
    ARTIST_CONTROLLER_ATTACH_METHOD,
    ARTIST_CONTROLLER_DETACH_METHOD,
    ARTIST_CONTROLLER_SET_DATA_METHOD,
    ARTIST_TOP_SONG_TITLE_FIELD,
    ARTIST_TOP_SONG_SUBTITLE_FIELD,
    ARTIST_TOP_SONG_CAPTION_FIELD,
    ARTIST_HEADER_TITLE_FIELD,
    COLLECTION_ITEM_GET_ID_METHOD,
    COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD,
    COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD,
    COLLECTION_ITEM_GET_TITLE_METHOD,
    COLLECTION_ITEM_SET_TITLE_METHOD,
    COLLECTION_ITEM_NOTIFY_CHANGE_METHOD,
    ARTWORK_GET_ARTWORK_TOKEN_METHOD,
    ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD,
    ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD,
    ARTWORK_GET_IMAGE_URL_METHOD,
    ARTWORK_GET_IMAGE_URLS_METHOD,
    ARTWORK_SET_IMAGE_URL_METHOD,
    ARTWORK_SET_IMAGE_URLS_METHOD,
    ARTWORK_NOTIFY_INITIAL_IMAGE_URL_METHOD,
    CUSTOM_IMAGE_SET_BITMAP_METHOD,
    CONTENT_ITEM_TITLE_GETTER,
    CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER,
    CONTENT_ITEM_ARTIST_GETTER,
    CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER,
    CONTENT_ITEM_SUBTITLE_GETTER,
    CONTENT_ITEM_COLLECTION_GETTER,
    CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER,
    CONTENT_ITEM_ID_GETTER,
    CONTENT_ITEM_PERSISTENT_ID_GETTER,
    CONTENT_ITEM_ASSET_ADAM_ID_GETTER,
    CONTENT_ITEM_REPORTING_ADAM_ID_GETTER,
    CONTENT_ITEM_FORMER_IDS_GETTER,
    CONTENT_ITEM_ARTIST_ID_GETTER,
    CONTENT_ITEM_ARTIST_ADAM_ID_GETTER,
    CONTENT_ITEM_ARTIST_STORE_ID_GETTER,
    CONTENT_ITEM_ARTIST_SUBSCRIPTION_STORE_ID_GETTER,
    CONTENT_ITEM_TITLE_FIELD,
    CONTENT_ITEM_ARTIST_FIELD,
    CONTENT_ITEM_COLLECTION_FIELD,
    CONTENT_ITEM_SET_TITLE_METHOD,
    CONTENT_ITEM_SET_ARTIST_METHOD,
    CONTENT_ITEM_SET_COLLECTION_METHOD,
    CONTENT_ITEM_SET_SUBTITLE_METHOD,
    CONTENT_ITEM_NOTIFY_CHANGE_METHOD,
    MEDIA_API_HOLDER_GET_MEDIA_API_METHOD,
    MEDIA_API_STOREFRONT_FIELD,
    MEDIA_API_DIRECT_QUERY_METHOD,
    CATALOG_RESPONSE_DATA_METHOD,
    CATALOG_ENTITY_ID_METHOD,
    CATALOG_ENTITY_SUBSCRIPTION_STORE_ID_METHOD,
    CATALOG_ENTITY_ASSET_ADAM_ID_METHOD,
    CATALOG_ENTITY_REPORTING_ADAM_ID_METHOD,
    CATALOG_ENTITY_FORMER_IDS_METHOD,
    CATALOG_ENTITY_ATTRIBUTES_METHOD,
    CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD,
    CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD,
    CATALOG_ATTRIBUTES_NAME_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD,
    CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD,
    CATALOG_ATTRIBUTES_SET_NAME_METHOD,
    CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD,
    CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD,
    CATALOG_ENTITY_RELATIONSHIPS_METHOD,
    CATALOG_RELATIONSHIP_ENTITIES_METHOD,
    CATALOG_RELATIONSHIP_DATA_METHOD,
    CATALOG_ATTRIBUTES_ISRC_METHOD,
    CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD,
    CATALOG_ATTRIBUTES_GENRE_NAME_METHOD,
    CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD,
    CUSTOM_TEXT_VIEW_SET_TEXT_METHOD,
    CUSTOM_TEXT_VIEW_ON_DRAW_METHOD,
    CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD,
    IN_APP_CONTAINER_SET_TITLE_METHOD,
    IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD,
}

internal data class AppleMusicHookTarget(
    val className: String,
    val methodName: String? = null,
    val parameterCount: Int? = null,
    val parameterTypeNames: List<String?>? = null,
    val returnTypeName: String? = null,
    val isStatic: Boolean? = null,
    val includeSynthetic: Boolean = false,
    val allowFirstMatch: Boolean = false,
    val runtimeMemberNames: Map<AppleMusicRuntimeMember, String> = emptyMap(),
    val requiredInvokedMethodDescriptors: List<String> = emptyList(),
    val requiredInvokedMethodNames: List<String> = emptyList(),
    val requiredCallerMethodNames: List<String> = emptyList(),
    val contract: AppleMusicHookContract? = null,
) {
    init {
        require(
            parameterTypeNames == null ||
                parameterCount == null ||
                parameterTypeNames.size == parameterCount
        ) {
            "parameterTypeNames must match parameterCount"
        }
    }

    fun runtimeMemberName(member: AppleMusicRuntimeMember): String =
        checkNotNull(runtimeMemberNames[member]) {
            "Missing runtime member $member for $className#${methodName ?: "<class>"}"
        }

    fun runtimeMemberNameOrNull(member: AppleMusicRuntimeMember): String? =
        runtimeMemberNames[member]
}

internal data class AppleMusicHookProfile(
    val id: String,
    val versionName: String,
    val versionCodes: Set<Long>,
    private val hookTargets: Map<AppleMusicHookPoint, List<AppleMusicHookTarget>>,
) {
    fun targets(hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        hookTargets[hookPoint].orEmpty()

    fun matches(version: AppleMusicVersion): Boolean =
        version.versionCode?.let(versionCodes::contains) == true ||
            version.versionName == versionName
}

/**
 * Apple Music 混淆版本档案的唯一维护入口。
 *
 * 后续版本更新流程：反编译新版 APK，确认每个 [AppleMusicHookPoint] 的目标类和方法，
 * 然后在 [KNOWN_PROFILES] 前部新增一份档案。未知版本会按“较新档案优先”的顺序尝试
 * 已知候选，但只有通过对应方法签名校验的目标才会被采用。
 */
internal object AppleMusicHookProfiles {
    private val APPLE_MUSIC_6_5_0 = AppleMusicHookProfile(
        id = "am-6.5.0-1580",
        versionName = "6.5.0",
        versionCodes = setOf(1580L),
        hookTargets = mapOf(
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                AppleMusicHookTarget("s8.E", "c0", 1),
            ),
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION to listOf(
                contentHttpLocalizationTarget(),
            ),
            AppleMusicHookPoint.EXO_MEDIA_PLAYER to listOf(exoMediaPlayerTarget()),
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID to listOf(exoAudioSessionIdTarget()),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE to listOf(
                localMediaPlayerControllerStateTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED to listOf(
                localMediaPlayerAudioVariantChangedTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED to listOf(
                localMediaPlayerMetadataUpdatedTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED to listOf(
                localMediaPlayerIndexChangedTarget(),
            ),
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to listOf(
                lyricsNetworkRequestTarget(),
            ),
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to listOf(lyricsCookieJarTarget()),
            AppleMusicHookPoint.EPOXY_FINAL_BIND to listOf(
                AppleMusicHookTarget(
                    className = "com.airbnb.epoxy.K",
                    methodName = "t",
                    parameterCount = 4,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD to "u",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.e0",
                    "onClick",
                    1,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD to "a",
                        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS to
                            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.z"),
            ),
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                lyricsRecyclerAdapterTarget("com.apple.android.music.player.R0"),
                lyricsRecyclerAdapterTarget("com.apple.android.music.player.z"),
            ),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget("z1.l"),
                AppleMusicHookTarget("z1.t"),
            ),
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.utils.l1\$a",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD to "c",
                    ),
                ),
            ),
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                AppleMusicHookTarget("l7.e8", "l", 0),
            ),
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.v0"),
            ),
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                    "B0",
                    0,
                ),
            ),
        ) + stableMetadataSurfaceHookTargets() +
            stableLibrarySurfaceHookTargets() + stableLyricsHookTargets() +
            stableAtmosDiagnosticHookTargets(),
    )

    private val APPLE_MUSIC_6_5_2 = AppleMusicHookProfile(
        id = "am-6.5.2-1586",
        versionName = "6.5.2",
        versionCodes = setOf(1586L),
        hookTargets = mapOf(
            // Verified from Apple Music 6.5.2 (1586) classes2.dex. These playback
            // callbacks retain the same exact descriptors as 6.5.0 and 6.5.1.
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID to listOf(exoAudioSessionIdTarget()),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED to listOf(
                localMediaPlayerAudioVariantChangedTarget(),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes2.dex.
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                    methodName = "buildStandardSwoosh\$lambda\$35",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                        "com.apple.android.music.mediaapi.models.Recommendation",
                        "com.apple.android.music.common.F0",
                        "com.apple.android.music.mediaapi.models.MediaEntity",
                        "java.util.List",
                    ),
                    returnTypeName = "com.airbnb.epoxy.l",
                    isStatic = true,
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes2.dex: common.L is the
            // renamed artwork lookup resolver; common.J no longer declares t().
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.L",
                    methodName = "t",
                    parameterCount = 1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.model.CollectionItemView"
                    ),
                    returnTypeName = "void",
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes2.dex: the controller's
            // real override has the concrete five-parameter signature; the inherited
            // Object[] overload belongs to Typed5EpoxyController and must not be hooked.
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.library2.LibraryMainContentEpoxyController",
                    methodName = "buildModels",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.library2.M",
                        "java.util.List",
                        "java.util.List",
                        "com.apple.android.music.library2.a",
                        "x6.c",
                    ),
                    returnTypeName = "void",
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes.dex: z0.s0 is the
            // NeverEqualPolicy singleton (its a(Object,Object) always returns false);
            // z0.v0 and z0.t0 no longer expose a static self-typed INSTANCE field.
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.s0"),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes.dex: C1.w.e(LiveData,
            // Composer) returns the z0.p0 state, whose runtime instance z0.q1 keeps the
            // same policy field b and getValue/setValue contract as C1.c.g on 6.5.1.
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
                AppleMusicHookTarget(
                    className = "C1.w",
                    methodName = "e",
                    parameterCount = 2,
                    returnTypeName = "z0.p0",
                    isStatic = true,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD to "b",
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD to
                            "getValue",
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD to
                            "setValue",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes2.dex: the lyrics
            // translation/pronunciation popup is opened by player.fragment.d0#onClick.
            // player.fragment.a0 still exists but no longer declares onClick, and the
            // 6.5.0 fallback player.fragment.e0 is not the button used on this page.
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.player.fragment.d0",
                    methodName = "onClick",
                    parameterCount = 1,
                    parameterTypeNames = listOf("android.view.View"),
                    returnTypeName = "void",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD to "a",
                        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS to
                            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes2.dex: the global metadata
            // dispatcher moved from player.f to player.e.
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.player.e",
                    methodName = "onMediaMetadataChanged",
                    parameterCount = 1,
                    returnTypeName = "void",
                ),
            ),
        ) + stableAtmosDiagnosticHookTargets(),
    )

    private val APPLE_MUSIC_6_5_1 = AppleMusicHookProfile(
        id = "am-6.5.1-1583",
        versionName = "6.5.1",
        versionCodes = setOf(1583L),
        hookTargets = mapOf(
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                AppleMusicHookTarget("s8.F", "c0", 1),
            ),
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION to listOf(
                contentHttpLocalizationTarget(),
            ),
            AppleMusicHookPoint.EXO_MEDIA_PLAYER to listOf(exoMediaPlayerTarget()),
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID to listOf(exoAudioSessionIdTarget()),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE to listOf(
                localMediaPlayerControllerStateTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED to listOf(
                localMediaPlayerAudioVariantChangedTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED to listOf(
                localMediaPlayerMetadataUpdatedTarget(),
            ),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED to listOf(
                localMediaPlayerIndexChangedTarget(),
            ),
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to listOf(
                lyricsNetworkRequestTarget(),
            ),
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to listOf(lyricsCookieJarTarget()),
            AppleMusicHookPoint.EPOXY_FINAL_BIND to listOf(
                AppleMusicHookTarget(
                    className = "com.airbnb.epoxy.J",
                    methodName = "t",
                    parameterCount = 4,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD to "u",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.a0",
                    "onClick",
                    1,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS to
                            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.A"),
            ),
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                lyricsRecyclerAdapterTarget("com.apple.android.music.player.A"),
                lyricsRecyclerAdapterTarget("com.apple.android.music.player.U0"),
            ),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget("z1.k"),
                AppleMusicHookTarget("z1.s"),
            ),
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.utils.i1\$a",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD to "c",
                    ),
                ),
            ),
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                AppleMusicHookTarget("l7.f8", "l", 0),
            ),
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.t0"),
            ),
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                    "A0",
                    0,
                ),
            ),
            // Verified from Apple Music 6.5.1 (1583) classes*.dex descriptors.
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                    methodName = "buildStandardSwoosh\$lambda\$35",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                        "com.apple.android.music.mediaapi.models.Recommendation",
                        "com.apple.android.music.common.D0",
                        "com.apple.android.music.mediaapi.models.MediaEntity",
                        "java.util.List",
                    ),
                    returnTypeName = "com.airbnb.epoxy.l",
                    isStatic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController\$Q",
                    methodName = "onModelBound",
                    parameterCount = 3,
                    returnTypeName = "void",
                    includeSynthetic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_MODEL to listOf(
                AppleMusicHookTarget("com.apple.android.music.l1"),
            ),
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.J",
                    methodName = "t",
                    parameterCount = 1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.model.CollectionItemView"
                    ),
                    returnTypeName = "void",
                    includeSynthetic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.model.extensions." +
                        "DelegatingCollectionItemView",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                            "getPersistentId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                            "getContentType",
                        AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD to
                            "getArtworkToken",
                        AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD to
                            "getAllArtworkTokens",
                        AppleMusicRuntimeMember.ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD to
                            "getFetchableArtworkToken",
                        AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URL_METHOD to "getImageUrl",
                        AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URLS_METHOD to "getImageUrls",
                        AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URL_METHOD to "setImageUrl",
                        AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URLS_METHOD to "setImageUrls",
                        AppleMusicRuntimeMember.ARTWORK_NOTIFY_INITIAL_IMAGE_URL_METHOD to
                            "notifyInitialImageUrl",
                    ),
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.CustomImageView",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.CUSTOM_IMAGE_SET_BITMAP_METHOD to "setBitmap",
                    ),
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                            "getPersistentId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                            "getContentType",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD to "getTitle",
                    ),
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.model.CollectionItemView",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                            "getPersistentId",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                            "getContentType",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD to "getTitle",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_SET_TITLE_METHOD to "setTitle",
                        AppleMusicRuntimeMember.COLLECTION_ITEM_NOTIFY_CHANGE_METHOD to
                            "notifyChange",
                        AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD to
                            "getArtworkToken",
                        AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD to
                            "getAllArtworkTokens",
                        AppleMusicRuntimeMember.ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD to
                            "getFetchableArtworkToken",
                    ),
                ),
            ),
        ) + stableMetadataSurfaceHookTargets() +
            stableLibrarySurfaceHookTargets() + stableLyricsHookTargets() +
            stableAtmosDiagnosticHookTargets(),
    )

    /** 新版本档案必须放在前面，未知版本回退时优先尝试较新的目标。 */
    private val KNOWN_PROFILES = listOf(
        APPLE_MUSIC_6_5_2,
        APPLE_MUSIC_6_5_1,
        APPLE_MUSIC_6_5_0,
    )

    /** Preserves the exact broad lookup constraints used by the pre-module Provider. */
    private fun stableMetadataSurfaceHookTargets() = mapOf(
        AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.player.f",
                "onMediaMetadataChanged",
                1,
            ),
        ),
        AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.player.fragment." +
                    "PlayerSongViewFragment\$PlayerListener",
                "onMediaMetadataChanged",
                1,
            ),
        ),
        AppleMusicHookPoint.IN_APP_QUEUE_UPDATE to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.player.queuefa.NewPlayerQueueViewModel",
                "updateQueue",
                5,
            ),
        ),
        AppleMusicHookPoint.IN_APP_HISTORY_UPDATE to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.player.queuefa.NewPlayerQueueViewModel",
                "updateHistory",
                1,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME to "Z8.d",
                ),
            ),
        ),
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT to listOf(
            AppleMusicHookTarget(
                "Y8.a",
                "B",
                1,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD to "A",
                    AppleMusicRuntimeMember.QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD to "l",
                    AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD to "b",
                    AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD to "d",
                    AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD to "a",
                    AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD to "I",
                    AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD to "a",
                    AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD to "b",
                ),
            ),
        ),
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND to listOf(
            AppleMusicHookTarget("Y8.a", "p", 2),
        ),
        AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.BaseContentItem",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.CONTENT_ITEM_ROLE to "base",
                    AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_GETTER to "getTitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER to
                        "getNowPlayingTitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_GETTER to "getArtistName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER to
                        "getNowPlayingSubtitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SUBTITLE_GETTER to "getSubTitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_GETTER to
                        "getCollectionName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER to
                        "getSubscriptionStoreId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ID_GETTER to "getId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_PERSISTENT_ID_GETTER to
                        "getPersistentId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ASSET_ADAM_ID_GETTER to
                        "getAssetAdamId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_REPORTING_ADAM_ID_GETTER to
                        "getReportingAdamId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_FORMER_IDS_GETTER to "getFormerIds",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ID_GETTER to "getArtistId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ADAM_ID_GETTER to
                        "getArtistAdamId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_STORE_ID_GETTER to
                        "getArtistStoreId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_SUBSCRIPTION_STORE_ID_GETTER to
                        "getArtistSubscriptionStoreId",
                    AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_FIELD to "name",
                    AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_FIELD to "artistName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_FIELD to "collectionName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SET_TITLE_METHOD to "setTitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SET_ARTIST_METHOD to "setArtistName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SET_COLLECTION_METHOD to
                        "setCollectionName",
                    AppleMusicRuntimeMember.CONTENT_ITEM_SET_SUBTITLE_METHOD to "setSubTitle",
                    AppleMusicRuntimeMember.CONTENT_ITEM_NOTIFY_CHANGE_METHOD to "notifyChange",
                ),
            ),
            AppleMusicHookTarget("com.apple.android.music.model.BasePlaybackItem"),
            AppleMusicHookTarget("com.apple.android.music.model.Song"),
            AppleMusicHookTarget("com.apple.android.music.model.AlbumCollectionItem"),
            AppleMusicHookTarget("com.apple.android.music.model.ArtistCollectionItem"),
            AppleMusicHookTarget("com.apple.android.music.model.MusicVideo"),
        ),
        AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.search2.RecentlySearchedEpoxyController",
                methodName = "setData",
                parameterCount = 1,
                parameterTypeNames = listOf("java.util.List"),
            ),
        ),
        AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.search2.RecentlySearchedEpoxyController",
                methodName = "onModelBound",
                parameterCount = 4,
                includeSynthetic = true,
            ),
        ),
        AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY to listOf(
            AppleMusicHookTarget("com.apple.android.music.mediaapi.models.MediaEntity"),
        ),
        AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY to listOf(
            AppleMusicHookTarget("com.apple.android.music.common.MainContentActivity"),
        ),
        AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS to listOf(
            AppleMusicHookTarget("com.apple.android.music.utils.AppSharedPreferences"),
        ),
        AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.Song",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.APPLE_SONG_SET_ID_METHOD to "setId",
                    AppleMusicRuntimeMember.APPLE_SONG_SET_QUEUE_ID_METHOD to "setQueueId",
                    AppleMusicRuntimeMember.APPLE_SONG_SET_HAS_LYRICS_METHOD to "setHasLyrics",
                    AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD to "getId",
                    AppleMusicRuntimeMember.LYRICS_SONG_QUEUE_ID_METHOD to "getQueueId",
                ),
            ),
        ),
        AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.player.O",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_CONTAINER_METHOD to "a",
                    AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD to "b",
                ),
            ),
        ),
        AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            ),
        ),
        AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.Artist",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.IN_APP_CONTAINER_SET_TITLE_METHOD to "setTitle",
                    AppleMusicRuntimeMember.IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD to
                        "notifyChange",
                ),
            ),
        ),
        AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.Album",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.IN_APP_CONTAINER_SET_TITLE_METHOD to "setTitle",
                    AppleMusicRuntimeMember.IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD to
                        "notifyChange",
                ),
            ),
        ),
        AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS to listOf(
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD to
                        "getMediaApi",
                    AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD to "s",
                    AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD to "B",
                    AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD to "getData",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD to "getId",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_SUBSCRIPTION_STORE_ID_METHOD to
                        "getSubscriptionStoreId",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_ASSET_ADAM_ID_METHOD to
                        "getAssetAdamId",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_REPORTING_ADAM_ID_METHOD to
                        "getReportingAdamId",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_FORMER_IDS_METHOD to "getFormerIds",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD to "getAttributes",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD to
                        "getPlayParams",
                    AppleMusicRuntimeMember.CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD to
                        "getCatalogId",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD to "getName",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD to
                        "getArtistName",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD to
                        "getAlbumName",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ID_METHOD to "getArtistId",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD to
                        "getArtistAdamId",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD to
                        "getArtistStoreId",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD to
                        "getArtistSubscriptionStoreId",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_NAME_METHOD to "setName",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD to
                        "setArtistName",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD to
                        "setAlbumName",
                    AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD to
                        "getRelationships",
                    AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD to
                        "getEntities",
                    AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD to "getData",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD to "getIsrc",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD to
                        "getGenreNames",
                    AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD to
                        "getGenreName",
                ),
            ),
        ),
    )

    /** Preserves the Library Compose/Epoxy lookup shapes verified by the current Provider. */
    private fun stableLibrarySurfaceHookTargets() = mapOf(
        AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.AlbumCollectionItem",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "model_album",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.Song",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "model_song",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.Song",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "media_api_song",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "song",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.LibrarySong",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "library_song",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "song",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.Album",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "media_api_album",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "album",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.LibraryAlbum",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "library_album",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "album",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.Artist",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "media_api_artist",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "artist",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.LibraryArtist",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE to "library_artist",
                    AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND to "artist",
                ),
            ),
        ),
        AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to listOf(
            AppleMusicHookTarget(
                "com.apple.android.music.library2.LibraryMainContentEpoxyController",
                "buildModels",
                5,
            ),
        ),
        AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT to listOf(
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                methodName = "J1",
                parameterCount = 2,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD to
                        "getRecentItemsLiveResult",
                ),
            ),
        ),
        AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
            AppleMusicHookTarget(
                className = "C1.c",
                methodName = "g",
                parameterCount = 2,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD to "b",
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD to "getValue",
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD to "setValue",
                ),
            ),
        ),
        AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES to listOf(
            AppleMusicHookTarget(
                className = "androidx.databinding.ViewDataBinding",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "binding",
                    AppleMusicRuntimeMember.DATA_BINDING_REGISTRATION_METHOD to "k0",
                    AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD to "y",
                    AppleMusicRuntimeMember.DATA_BINDING_EXECUTE_METHOD to "n",
                    AppleMusicRuntimeMember.DATA_BINDING_SET_VARIABLE_METHOD to "h0",
                ),
            ),
            AppleMusicHookTarget(
                className = "androidx.databinding.i",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "observable",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.BR",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "br",
                    AppleMusicRuntimeMember.DATA_BINDING_TITLE_VARIABLE_FIELD to "title",
                    AppleMusicRuntimeMember.DATA_BINDING_SUBTITLE_VARIABLE_FIELD to "subtitle",
                ),
            ),
            AppleMusicHookTarget(
                className = "androidx.recyclerview.widget.RecyclerView",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "recycler",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.model.BaseContentItem",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "content_item",
                ),
            ),
        ),
        AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES to listOf(
            AppleMusicHookTarget(
                className = "androidx.recyclerview.widget.RecyclerView",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "recycler",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.MediaEntity",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "media_entity",
                    AppleMusicRuntimeMember.COLLECTION_ENTITY_EXPLICIT_METHOD to "isExplicit",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.Album",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "album_entity",
                ),
            ),
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.collection.mediaapi.controller.AlbumPageController",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "album_controller",
                    AppleMusicRuntimeMember.COLLECTION_ALBUM_HEADER_BUILD_METHOD to
                        "buildHeaderModelInternal",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_ATTACH_METHOD to
                        "onAttachedToRecyclerView",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_DETACH_METHOD to
                        "onDetachedFromRecyclerView",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_SET_DATA_METHOD to "setData",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.j",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "album_header_model",
                ),
            ),
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.collection.mediaapi.controller.PlaylistPageController",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "playlist_controller",
                    AppleMusicRuntimeMember.COLLECTION_PLAYLIST_BUILD_ITEM_METHOD to
                        "buildItemModel",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_ATTACH_METHOD to
                        "onAttachedToRecyclerView",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_DETACH_METHOD to
                        "onDetachedFromRecyclerView",
                    AppleMusicRuntimeMember.COLLECTION_CONTROLLER_FORCE_BUILD_METHOD to
                        "requestForcedModelBuild",
                ),
            ),
            AppleMusicHookTarget(
                className = "k6.b",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "album_row_model",
                ),
            ),
            AppleMusicHookTarget(
                className = "k6.d",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "playlist_row_model",
                    AppleMusicRuntimeMember.COLLECTION_PLAYLIST_TITLE_FIELD to "M",
                    AppleMusicRuntimeMember.COLLECTION_PLAYLIST_SUBTITLE_FIELD to "P",
                ),
            ),
        ),
        AppleMusicHookPoint.ARTIST_SURFACE_CLASSES to listOf(
            AppleMusicHookTarget(
                className = "androidx.recyclerview.widget.RecyclerView",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "recycler",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.mediaapi.models.MediaEntity",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "media_entity",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.profiles.BaseProfileEpoxyController",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "base_controller",
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_BUILD_METHOD to
                        "addSwipingChartItemA2",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.profiles.ArtistEpoxyController",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "artist_controller",
                    AppleMusicRuntimeMember.ARTIST_PROFILE_BUILD_METHOD to "buildModels",
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_ATTACH_METHOD to
                        "onAttachedToRecyclerView",
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_DETACH_METHOD to
                        "onDetachedFromRecyclerView",
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_SET_DATA_METHOD to "setData",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.h1",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "top_song_model",
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD to "a",
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD to "L",
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD to "P",
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_CAPTION_FIELD to "H",
                ),
            ),
            AppleMusicHookTarget(
                className = "com.apple.android.music.V",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE to "header_model",
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD to "a",
                    AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD to "x",
                ),
            ),
        ),
    )

    private fun contentHttpLocalizationTarget() = AppleMusicHookTarget(
        className = "u8.a",
        methodName = "a",
        parameterCount = 1,
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD to "e",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD to "a",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD to "c",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD to "d",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD to "b",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD to "h",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD to "d",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD to "b",
            AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD to "e",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_REQUEST_FIELD to "a",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_HEADERS_FIELD to "f",
            AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_VALUES_FIELD to "a",
        ),
    )

    private fun exoMediaPlayerTarget() = AppleMusicHookTarget(
        className = "com.apple.android.music.playback.player.ExoMediaPlayer",
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.EXO_SEEK_METHOD to "seekToPosition",
            AppleMusicRuntimeMember.EXO_PLAY_METHOD to "play",
            AppleMusicRuntimeMember.EXO_PAUSE_METHOD to "pause",
            AppleMusicRuntimeMember.EXO_STOP_METHOD to "stop",
            AppleMusicRuntimeMember.EXO_RELEASE_METHOD to "release",
            AppleMusicRuntimeMember.EXO_CURRENT_POSITION_METHOD to "getCurrentPosition",
        ),
    )

    /**
     * Verified from the original classes2.dex of Apple Music 6.5.0 (1580),
     * 6.5.1 (1583), and 6.5.2 (1586).
     */
    private fun exoAudioSessionIdTarget() = AppleMusicHookTarget(
        className = "com.apple.android.music.playback.player.ExoMediaPlayer",
        methodName = "onAudioSessionId",
        parameterCount = 1,
        parameterTypeNames = listOf("int"),
        returnTypeName = "void",
        isStatic = false,
    )

    private fun localMediaPlayerControllerStateTarget() = AppleMusicHookTarget(
        className =
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
        methodName = "onPlaybackStateChanged",
        parameterCount = 3,
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.PLAYBACK_PLAYER_CURRENT_ITEM_METHOD to "getCurrentItem",
            AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD to "getItem",
            AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD to "getPlaybackQueueId",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_TITLE_METHOD to "getTitle",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD to "getArtistName",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD to "getGenreName",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_DURATION_METHOD to "getDuration",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD to
                "getSubscriptionStoreId",
            AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD to
                "getPersistentId",
        ),
    )

    /**
     * Original DEX evidence for Apple Music 6.5.0-6.5.2 shows that variant 4 is
     * TRACK_VARIANTS_DOLBY_ATMOS and this callback carries the exact active player.
     */
    private fun localMediaPlayerAudioVariantChangedTarget() = AppleMusicHookTarget(
        className =
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
        methodName = "onPlaybackAudioVariantChanged",
        parameterCount = 5,
        parameterTypeNames = listOf(
            "com.apple.android.music.playback.player.MediaPlayer",
            "int",
            "long",
            "com.google.android.exoplayer2.Format",
            "com.google.android.exoplayer2.Format",
        ),
        returnTypeName = "void",
        isStatic = false,
        runtimeMemberNames = debugAtmosFormatRuntimeMembers(includeHolder = false),
    )

    /**
     * Diagnostic-only playback targets verified from the original classes.dex/classes2.dex of
     * Apple Music 6.5.0 (1580), 6.5.1 (1583), and 6.5.2 (1586).
     */
    private fun stableAtmosDiagnosticHookTargets(): Map<
        AppleMusicHookPoint,
        List<AppleMusicHookTarget>,
    > = mapOf(
        AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID to listOf(
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.playback.renderer.SVMediaCodecAudioRenderer",
                methodName = "invalidatePeriodId",
                parameterCount = 2,
                parameterTypeNames = listOf(
                    "com.google.android.exoplayer2.source.SampleStream",
                    "long",
                ),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT to listOf(
            AppleMusicHookTarget(
                className =
                    "com.apple.android.music.playback.renderer.SVMediaCodecAudioRenderer",
                methodName = "onInputFormatChanged",
                parameterCount = 1,
                parameterTypeNames = listOf("com.google.android.exoplayer2.FormatHolder"),
                returnTypeName = "void",
                isStatic = false,
                runtimeMemberNames = debugAtmosFormatRuntimeMembers(includeHolder = true),
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION to listOf(
            AppleMusicHookTarget(
                className = "com.google.android.exoplayer2.audio.MediaCodecAudioRenderer",
                methodName = "onAudioSessionId",
                parameterCount = 1,
                parameterTypeNames = listOf("int"),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER to listOf(
            AppleMusicHookTarget(
                className = "com.google.android.exoplayer2.audio.MediaCodecAudioRenderer",
                methodName = "processOutputBuffer",
                parameterCount = 10,
                parameterTypeNames = listOf(
                    "long",
                    "long",
                    "android.media.MediaCodec",
                    "java.nio.ByteBuffer",
                    "int",
                    "int",
                    "long",
                    "boolean",
                    "boolean",
                    "com.google.android.exoplayer2.Format",
                ),
                returnTypeName = "boolean",
                isStatic = false,
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_PERIOD_ID to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                methodName = "invalidatePeriodId",
                parameterCount = 2,
                parameterTypeNames = listOf(
                    "com.google.android.exoplayer2.source.SampleStream",
                    "long",
                ),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                methodName = "onStreamChanged",
                parameterCount = 2,
                parameterTypeNames = listOf(
                    "[Lcom.google.android.exoplayer2.Format;",
                    "long",
                ),
                returnTypeName = "void",
                isStatic = false,
                runtimeMemberNames = debugAtmosFormatRuntimeMembers(includeHolder = false),
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_SESSION to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                methodName = "onAudioSessionId",
                parameterCount = 1,
                parameterTypeNames = listOf("int"),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
        AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                methodName = "maybeNotifyFirstDecodedBuffer",
                parameterCount = 0,
                parameterTypeNames = emptyList(),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
    )

    private fun debugAtmosFormatRuntimeMembers(
        includeHolder: Boolean,
    ): Map<AppleMusicRuntimeMember, String> = buildMap {
        if (includeHolder) {
            put(AppleMusicRuntimeMember.DEBUG_FORMAT_HOLDER_FORMAT_FIELD, "format")
        }
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_CODECS_FIELD, "codecs")
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_SAMPLE_MIME_TYPE_FIELD, "sampleMimeType")
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_LOUDNESS_FIELD, "loudness")
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_CHANNEL_COUNT_FIELD, "channelCount")
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_SAMPLE_RATE_FIELD, "sampleRate")
        put(AppleMusicRuntimeMember.DEBUG_FORMAT_BITRATE_FIELD, "bitrate")
    }

    /** Preserves the pre-refactor name/count-only lookup without tightening its signature. */
    private fun localMediaPlayerMetadataUpdatedTarget() = AppleMusicHookTarget(
        className =
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
        methodName = "onMetadataUpdated",
        parameterCount = 2,
        includeSynthetic = true,
        allowFirstMatch = true,
    )

    /** Preserves the pre-refactor name/count-only lookup without tightening its signature. */
    private fun localMediaPlayerIndexChangedTarget() = AppleMusicHookTarget(
        className =
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
        methodName = "onPlaybackIndexChanged",
        parameterCount = 3,
        includeSynthetic = true,
        allowFirstMatch = true,
    )

    private fun lyricsNetworkRequestTarget() = AppleMusicHookTarget(
        className = "t8.N0",
        methodName = "z",
        allowFirstMatch = true,
    )

    private fun lyricsCookieJarTarget() = AppleMusicHookTarget(
        className = "s8.b",
        methodName = "d",
        parameterCount = 1,
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.LYRICS_COOKIE_NAME_FIELD to "a",
            AppleMusicRuntimeMember.LYRICS_COOKIE_VALUE_FIELD to "b",
        ),
    )

    private fun lyricsRecyclerAdapterTarget(className: String) = AppleMusicHookTarget(
        className = className,
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD to "B",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD to "C",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD to "b",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_AT_METHOD to "a",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD to "k",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD to "i",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD to "l",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD to "T",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD to "d",
            AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD to "e",
        ),
    )

    private fun stableLyricsHookTargets(): Map<AppleMusicHookPoint, List<AppleMusicHookTarget>> =
        mapOf(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.utils.AppSharedPreferences",
                    "setLyricsTranslationSelected",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.utils.AppSharedPreferences",
                    "setLyricsPronunciationSelected",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.playback.util.LocaleUtil",
                    "matchToSystemLyricsScript",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f"
                ),
            ),
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
                    "loadLyrics",
                    1,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD to
                            "getHtmlLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD to
                            "getHtmlTranslationLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD to
                            "getHtmlPronunciationLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD to
                            "getHtmlBackgroundVocalsLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD to
                            "getHtmlTranslatedBackgroundVocalsLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD to
                            "getHtmlPronunciationBackgroundVocalsLineText",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD to
                            "getPronunciationWords",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD to
                            "getPronunciationBackgroundWords",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD to "getWords",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD to
                            "getBackgroundWords",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD to
                            "setTranslation",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD to
                            "hasTranslation",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD to
                            "setPronunciation",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD to
                            "hasPronunciation",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD to "get",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD to "get",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD to "size",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD to "address",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD to
                            "getSections",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD to "getLines",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD to "getBegin",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD to "getEnd",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD to "getDuration",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD to "getWordId",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD to "isWhitespace",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD to
                            "getPronunciationLanguages",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD to
                            "getTranslationLanguages",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SET_ADAM_ID_METHOD to
                            "setAdamId",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SET_QUEUE_ID_METHOD to
                            "setQueueId",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_QUEUE_ID_METHOD to
                            "getQueueId",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_AGENTS_METHOD to "getAgents",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_METHOD to "getAgent",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_NAME_TYPES_METHOD to
                            "getNameTypes_",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_TYPE_METHOD to "getType_",
                        AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_ID_METHOD to "getId",
                        AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD to "getAdamId",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD to
                            "getCurrentSystemLyricsLanguage",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER to
                            "getLyricsResult",
                        AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME to
                            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
                    "buildTimeRangeToLyricsMap",
                    1,
                ),
            ),
            // Verified from the original Apple Music 6.5.1 (1583) classes2.dex:
            // PlayerLyricsViewFragment.I2(SongInfoPtr)V is the main lyrics-result consumer. It
            // validates SongInfoNative.adamId against the current BaseContentItem.getId(), then
            // installs the lyrics adapter and closes the loading/no-lyrics state. R2 only updates
            // translation/pronunciation availability and must not be used as the main result path.
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    "I2",
                    1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
                    ),
                    returnTypeName = "void",
                ),
            ),
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    "R2",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    "onCreateView",
                    3,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD to
                            "getRecyclerView",
                        AppleMusicRuntimeMember.LYRICS_UI_ROOT_VIEW_GETTER to
                            "getView",
                        AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD to "i0",
                        AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD to "a0",
                        AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD to "k0",
                        AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD to "j1",
                        AppleMusicRuntimeMember.LYRICS_UI_LOADING_PROGRESS_RESOURCE_NAME to
                            "loading_progress",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER to
                            "getPronunciationSelectedLiveResult",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER to
                            "getPronunciationAvailableLiveResult",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER to
                            "getTranslationSelectedLiveResult",
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER to
                            "getTranslationAvailableLiveResult",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_UI_ON_RESUME to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    "onResume",
                    0,
                ),
            ),
            AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                    "onDestroyView",
                    0,
                ),
            ),
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.ttml.javanative.model.LyricsWordVector"
                ),
            ),
            // Verified from Apple Music 6.5.1 (1583) classes3.dex:
            // com.apple.android.music.ttml.f#e 直接以 MediaEntity.getTtml() 的
            // TTML 字符串调用 TTMLParser$TTMLParserNative.songInfoFromTTML(String)。
            AppleMusicHookPoint.LYRICS_TTML_PARSER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative",
                    "songInfoFromTTML",
                    1,
                    parameterTypeNames = listOf("java.lang.String"),
                    returnTypeName =
                        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
                ),
            ),
            // Verified from Apple Music 6.5.1 (1583) classes2.dex:
            // PlaybackItem.hasLyrics()Z / hasTimeSyncedLyrics()Z 由 BasePlaybackItem 实现，
            // 播放页歌词按钮的可用状态读取该值。
            AppleMusicHookPoint.LYRICS_AVAILABILITY_HAS_LYRICS to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.model.BasePlaybackItem",
                    "hasLyrics",
                    0,
                    returnTypeName = "boolean",
                ),
            ),
            AppleMusicHookPoint.LYRICS_AVAILABILITY_TIME_SYNCED to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.model.BasePlaybackItem",
                    "hasTimeSyncedLyrics",
                    0,
                    returnTypeName = "boolean",
                ),
            ),
            // Verified from the original Apple Music 6.5.1 (1583) classes2.dex:
            // player.e1.i(PlaybackItem)Z gates the playback-page lyrics entry with the global
            // feature switch and (hasLyrics() || hasCustomLyrics()). This diagnostic target must
            // remain observational; it does not replace the calculated result.
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.player.e1",
                    methodName = "i",
                    parameterCount = 1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.model.PlaybackItem"
                    ),
                    returnTypeName = "boolean",
                    isStatic = true,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD to
                            "hasLyrics",
                        AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD to
                            "hasCustomLyrics",
                    ),
                ),
            ),
            // Verified from the same original DEX. l7.N2.l() is the generated DataBinding
            // execute method; its PlaybackItem is inherited as M2.i0 and the lyrics ImageView as
            // M2.a0. The method passes e1.i(item) to a0.setEnabled(result).
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE to listOf(
                AppleMusicHookTarget(
                    className = "l7.N2",
                    methodName = "l",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "void",
                    isStatic = false,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD to "i0",
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD to "a0",
                    ),
                ),
            ),
            AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.views.CustomTextView",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD to
                            "setTypeface",
                        AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TEXT_METHOD to "setText",
                        AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_ON_DRAW_METHOD to "onDraw",
                        AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD to "f",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_GRADIENT_MASK_UPDATE to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout\$a",
                    "b",
                    3,
                    parameterTypeNames = listOf(
                        "[I",
                        "[F",
                        "java.lang.Float",
                    ),
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_GRADIENT_LAYOUT_CLASS_NAME to
                            "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout",
                        AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_START_CHILD_FIELD to "c",
                        AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_END_CHILD_FIELD to "d",
                        AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_POSITIONS_FIELD to "h",
                        AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_FRACTION_FIELD to "i",
                    ),
                ),
            ),
        )

    fun profileFor(version: AppleMusicVersion): AppleMusicHookProfile? =
        KNOWN_PROFILES.firstOrNull { profile -> profile.matches(version) }

    fun exactTargets(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<AppleMusicHookTarget> = profileFor(version)?.targets(hookPoint).orEmpty()

    fun candidates(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<AppleMusicHookTarget> {
        val exact = exactTargets(version, hookPoint)
        return (exact + KNOWN_PROFILES.flatMap { profile -> profile.targets(hookPoint) })
            .distinct()
    }
}

internal data class ResolvedAppleMusicHookClass(
    val target: AppleMusicHookTarget,
    val clazz: Class<*>,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
)

internal data class ResolvedAppleMusicHookMethod(
    val target: AppleMusicHookTarget,
    val method: Method,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
)

/** 统一负责按 Apple Music 版本加载并校验混淆 Hook 目标。 */
internal class AppleMusicHookResolver(
    val version: AppleMusicVersion,
    private val classLookup: (String) -> Class<*>,
    private val dexKitResolver: AppleMusicDexKitResolver? = null,
) {
    constructor(version: AppleMusicVersion, classLoader: ClassLoader) : this(
        version = version,
        classLookup = classLoader::loadClass,
    )

    constructor(
        version: AppleMusicVersion,
        application: android.app.Application,
        nativeLibraryDir: String,
    ) : this(
        version = version,
        classLookup = application.classLoader::loadClass,
        dexKitResolver = AppleMusicDexKitResolver(
            application = application,
            classLoader = application.classLoader,
            nativeLibraryDir = nativeLibraryDir,
        ),
    )

    val profile: AppleMusicHookProfile? = AppleMusicHookProfiles.profileFor(version)

    fun configuredClassNames(hookPoint: AppleMusicHookPoint): Set<String> {
        val exact = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val targets = if (exact.isNotEmpty()) {
            exact
        } else {
            AppleMusicHookProfiles.candidates(version, hookPoint)
        }
        return targets.mapTo(LinkedHashSet(), AppleMusicHookTarget::className)
    }

    /**
     * 加载一个 Hook 点在当前精确档案里的全部类。精确目标全部缺失时才进入兼容回退，
     * 避免在已知版本里同时 Hook 旧版本碰巧仍存在、但语义已经变化的类。
     */
    fun resolveClasses(hookPoint: AppleMusicHookPoint): List<ResolvedAppleMusicHookClass> {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val exactClasses = exactTargets.mapNotNull { target ->
            loadClass(hookPoint, target, compatibilityFallback = false)
                ?.let { resolved -> repairAndRecordClass(hookPoint, resolved, target.className) }
        }
        val resolved = LinkedHashMap<String, ResolvedAppleMusicHookClass>()
        exactClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }

        val compatibilityClasses = AppleMusicHookProfiles.candidates(version, hookPoint)
            .filterNot { target -> exactClasses.any { it.target.className == target.className } }
            .mapNotNull { target -> loadClass(hookPoint, target, compatibilityFallback = true) }
            .map { resolved ->
                repairAndRecordClass(hookPoint, resolved, resolved.target.className)
            }
        compatibilityClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }

        val dexKitClasses = dexKitResolver?.resolveClasses(
            hookPoint,
            AppleMusicHookProfiles.candidates(version, hookPoint).filterNot { target ->
                resolved.values.any { it.target.className == target.className }
            },
        ).orEmpty()
        dexKitClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }
        if (resolved.isNotEmpty()) return resolved.values.toList()

        return resolveDexKitMethod(hookPoint)?.let { resolved ->
            listOf(
                ResolvedAppleMusicHookClass(
                    target = resolved.target,
                    clazz = resolved.method.declaringClass,
                    compatibilityFallback = true,
                    contractReason = resolved.contractReason,
                ),
            )
        }.orEmpty()
    }

    /** 解析单个类；精确档案缺失时才尝试已知版本候选。通过语义契约校验才允许返回。 */
    fun resolveClass(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookClass {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            val contractResult = AppleMusicHookContracts.validate(
                HookContractContext(
                    hookPoint = hookPoint,
                    target = target,
                    clazz = clazz,
                    method = null,
                    classLookup = classLookup,
                    dexKitResolver = dexKitResolver,
                ),
            )
            if (contractResult is ContractResult.Rejected) {
                failures += "${target.className}:contract:${contractResult.reason}"
                return@forEach
            }
            return repairAndRecordClass(
                hookPoint = hookPoint,
                baselineClassName = target.className,
                resolved = ResolvedAppleMusicHookClass(
                    target = target,
                    clazz = clazz,
                    compatibilityFallback = target !in exactTargets,
                    contractReason = if (target !in exactTargets) "contract_passed" else null,
                ),
            )
        }
        dexKitResolver?.resolveClasses(hookPoint, AppleMusicHookProfiles.candidates(version, hookPoint))
            ?.firstOrNull()
            ?.let { return it }
        resolveDexKitMethod(hookPoint)?.let { resolved ->
            return ResolvedAppleMusicHookClass(
                target = resolved.target,
                clazz = resolved.method.declaringClass,
                compatibilityFallback = true,
                contractReason = resolved.contractReason,
            )
        }
        throw ClassNotFoundException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    /** 解析单个方法；候选类存在但方法签名或语义契约不符时继续尝试下一版本候选。 */
    fun resolveMethod(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookMethod {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:class:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            val matchingMethods = allDeclaredMethods(
                clazz = clazz,
                includeSynthetic = target.includeSynthetic,
            )
                .filter { method -> methodMatches(hookPoint, target, method) }
                .toList()
            if (matchingMethods.size == 1 || target.allowFirstMatch && matchingMethods.isNotEmpty()) {
                val method = matchingMethods.first().apply { isAccessible = true }
                val contractResult = AppleMusicHookContracts.validate(
                    HookContractContext(
                        hookPoint = hookPoint,
                        target = target,
                        clazz = clazz,
                        method = method,
                        classLookup = classLookup,
                        dexKitResolver = dexKitResolver,
                    ),
                )
                if (contractResult is ContractResult.Rejected) {
                    failures += "${target.className}#${method.name}:contract:${contractResult.reason}"
                    return@forEach
                }
                return repairAndRecordMethod(
                    hookPoint = hookPoint,
                    baselineClassName = target.className,
                    resolved = ResolvedAppleMusicHookMethod(
                        target = target,
                        method = method,
                        compatibilityFallback = target !in exactTargets,
                        contractReason = if (target !in exactTargets) "contract_passed" else null,
                    ),
                )
            }
            failures += if (matchingMethods.isEmpty()) {
                "${target.className}#${target.methodName}:signature"
            } else {
                "${target.className}#${target.methodName}:ambiguous(${matchingMethods.size})"
            }
        }
        resolveDexKitMethod(hookPoint)?.let { return it }

        throw NoSuchMethodException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    private fun resolveDexKitMethod(
        hookPoint: AppleMusicHookPoint,
    ): ResolvedAppleMusicHookMethod? {
        val candidates = AppleMusicHookProfiles.candidates(version, hookPoint)
        if (candidates.none { it.methodName != null || it.parameterCount != null }) return null
        return dexKitResolver?.resolveMethod(
            hookPoint = hookPoint,
            templates = candidates,
            validator = { template, method ->
                val matches = methodMatches(
                    hookPoint = hookPoint,
                    target = template.copy(
                        className = method.declaringClass.name,
                        methodName = method.name,
                        parameterCount = method.parameterCount,
                        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
                        returnTypeName = method.returnType.name,
                        isStatic = Modifier.isStatic(method.modifiers),
                    ),
                    method = method,
                )
                if (!matches) return@resolveMethod false
                val contractResult = AppleMusicHookContracts.validate(
                    HookContractContext(
                        hookPoint = hookPoint,
                        target = template,
                        clazz = method.declaringClass,
                        method = method,
                        classLookup = classLookup,
                        dexKitResolver = dexKitResolver,
                    ),
                )
                contractResult is ContractResult.Passed
            },
        )
    }

    private fun repairAndRecordClass(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookClass,
        baselineClassName: String,
    ): ResolvedAppleMusicHookClass {
        val repairedTarget = dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.clazz,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        dexKitResolver?.recordBaseline(
            hookPoint = hookPoint,
            target = repairedTarget,
            clazz = resolved.clazz,
            baselineClassName = baselineClassName,
        )
        return resolved.copy(target = repairedTarget)
    }

    private fun repairAndRecordMethod(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookMethod,
        baselineClassName: String,
    ): ResolvedAppleMusicHookMethod {
        val repairedTarget = dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.method.declaringClass,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        dexKitResolver?.recordMethodBaseline(
            hookPoint = hookPoint,
            target = repairedTarget,
            method = resolved.method,
            baselineClassName = baselineClassName,
        )
        return resolved.copy(target = repairedTarget)
    }

    private fun loadClass(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        compatibilityFallback: Boolean,
    ): ResolvedAppleMusicHookClass? = runCatching {
        val clazz = classLookup(target.className)
        val contractResult = AppleMusicHookContracts.validate(
            HookContractContext(
                hookPoint = hookPoint,
                target = target,
                clazz = clazz,
                method = null,
                classLookup = classLookup,
                dexKitResolver = dexKitResolver,
            ),
        )
        if (contractResult is ContractResult.Rejected) return null
        ResolvedAppleMusicHookClass(
            target = target,
            clazz = clazz,
            compatibilityFallback = compatibilityFallback,
            contractReason = if (compatibilityFallback) "contract_passed" else null,
        )
    }.getOrNull()

    private fun methodMatches(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        method: Method,
    ): Boolean {
        if (target.methodName != null && method.name != target.methodName) return false
        if (target.parameterCount != null && method.parameterCount != target.parameterCount) {
            return false
        }
        target.parameterTypeNames?.forEachIndexed { index, expectedName ->
            if (expectedName != null && method.parameterTypes[index].name != expectedName) {
                return false
            }
        }
        if (target.returnTypeName != null && method.returnType.name != target.returnTypeName) {
            return false
        }
        if (target.isStatic != null && Modifier.isStatic(method.modifiers) != target.isStatic) {
            return false
        }
        return when (hookPoint) {
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION ->
                Map::class.java.isAssignableFrom(method.returnType)

            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
            AppleMusicHookPoint.EXO_MEDIA_PLAYER,
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_PERIOD_ID,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_SESSION,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST,
            AppleMusicHookPoint.LYRICS_COOKIE_JAR,
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION,
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION,
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW,
            AppleMusicHookPoint.LYRICS_UI_ON_RESUME,
            AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW,
            AppleMusicHookPoint.LYRICS_TTML_PARSER,
            AppleMusicHookPoint.LYRICS_AVAILABILITY_HAS_LYRICS,
            AppleMusicHookPoint.LYRICS_AVAILABILITY_TIME_SYNCED,
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR,
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE,
            AppleMusicHookPoint.LYRICS_GRADIENT_MASK_UPDATE -> true

            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER,
            AppleMusicHookPoint.IN_APP_QUEUE_UPDATE,
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND,
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD,
            AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT,
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE,
            AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES,
            AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES,
            AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES,
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES -> true

            AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
            AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY -> true

            AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER ->
                method.name == "setData" &&
                    !method.isBridge &&
                    method.parameterCount == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0])

            AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND ->
                !method.isBridge && method.parameterCount == 4

            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
            AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY,
            AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS,
            AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS,
            AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS,
            AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS,
            AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS -> true

            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS -> true

            AppleMusicHookPoint.EPOXY_FINAL_BIND -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 4 &&
                    parameters[0] == parameters[1] &&
                    List::class.java.isAssignableFrom(parameters[2]) &&
                    parameters[3] == Int::class.javaPrimitiveType
            }

            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 1 &&
                    parameters[0].name == "android.view.View"
            }

            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING ->
                method.returnType == Void.TYPE && method.parameterCount == 0

            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER ->
                method.parameterCount == 0 &&
                    method.returnType.name ==
                    "com.apple.android.music.library2.LibraryViewModel"

            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
            AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS,
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER,
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
            AppleMusicHookPoint.LISTEN_NOW_MODEL,
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW -> true
        }
    }

    private fun allDeclaredMethods(
        clazz: Class<*>,
        includeSynthetic: Boolean,
    ): Sequence<Method> =
        generateSequence(clazz) { current -> current.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filter { method ->
                includeSynthetic || (!method.isBridge && !method.isSynthetic)
            }
            .distinctBy { method ->
                method.name to method.parameterTypes.joinToString(separator = ",") { it.name }
            }
}
