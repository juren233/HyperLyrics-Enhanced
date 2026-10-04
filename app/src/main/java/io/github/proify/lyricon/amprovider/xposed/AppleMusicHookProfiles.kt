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
    ACTIVITY_THEME_CREATE,
    ACTIVITY_THEME_RESTART,
    THEME_MODE_EMIT,
    APP_COMPAT_THEME_STATE,
    SETTINGS_DATA_CATEGORY_BUILD,
    SETTINGS_CELLULAR_SIM_CHECK,
    CELLULAR_AVAILABILITY,
    MEDIA_API_LOCALIZATION,
    CONTENT_HTTP_LOCALIZATION,
    EXO_MEDIA_PLAYER,
    EXO_PLAYER_STATE_CHANGED,
    EXO_AUDIO_SESSION_ID,
    LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
    LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
    ATMOS_TRACK_LOUDNESS_METADATA,
    ATMOS_FORMAT_COPY_WITH_LOUDNESS,
    ATMOS_FORMAT_COPY_WITH_MANIFEST_INFO,
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
    SEARCH_RESULTS_MODEL_BOUND,
    SEARCH_CONTROLLER_ADAPTER,
    SEARCH_ADAPTER_MODEL,
    RECYCLER_NOTIFY_ITEM_CHANGED,
    BROWSE_COMPOSE_ITEM,
    BROWSE_COMPOSER_SCOPE,
    BROWSE_COMPOSER_USE_SCOPE,
    BROWSE_SCOPE_INVALIDATE,
    RADIO_STATION_CLASS,
    RADIO_SEARCH_SESSION,
    RADIO_SEARCH_START,
    RADIO_SEARCH_RESULT,
    RADIO_SEARCH_CANCEL,
    RADIO_SEARCH_RESPONSE_RESULTS,
    RADIO_SEARCH_ARTISTS,
    RADIO_SEARCH_ENTITIES,
    APPLE_MAIN_CONTENT_ACTIVITY,
    APPLE_MEDIA_LEGACY_ACTIVITY,
    APPLE_MEDIA_SESSION_SERVICE,
    APPLE_MEDIA_MAIN_NEW_INTENT,
    APPLE_MEDIA_MAIN_POST_RESUME,
    APPLE_MEDIA_MAIN_VIEW_MODEL,
    APPLE_MEDIA_PLAYER_VIEW_CREATED,
    APPLE_MEDIA_PLAYER_VIEW_DESTROYED,
    APPLE_MEDIA_PLAYER_VIEW_MODEL,
    APPLE_MEDIA_PLAYER_EXPAND,
    APPLE_SHARED_PREFERENCES_CLASS,
    APPLE_SONG_MODEL_CLASS,
    APPLE_PLAYER_UTIL_CLASS,
    PLAYER_LYRICS_VIEW_MODEL_CLASS,
    IN_APP_CONTAINER_ARTIST_CLASS,
    IN_APP_CONTAINER_ALBUM_CLASS,
    MEDIA_API_REPOSITORY_HOLDER_CLASS,
    MEDIA_API_CATALOG_REQUEST_EXECUTOR,
    MEDIA_API_AMP_HTTP_INTERCEPTOR,
    COMPOSE_NEVER_EQUAL_POLICY,
    LIBRARY_COMPOSE_VIEW_MODEL_GETTER,
    LIBRARY_EPOXY_BUILD,
    LIBRARY_COMPOSE_CONTENT,
    COMPOSE_OBSERVE_AS_STATE,
    LIBRARY_ENTITY_CLASSES,
    DATA_BINDING_RUNTIME_CLASSES,
    COLLECTION_SURFACE_CLASSES,
    ARTIST_SURFACE_CLASSES,
    ALBUM_COMPOSE_RESUME,
    ALBUM_COMPOSE_CONTENT,
    ALBUM_COMPOSE_VIEW_MODEL_GETTER,
    ALBUM_COMPOSE_PAGE_ID,
    ALBUM_COMPOSE_CURRENT_DATA,
    ALBUM_COMPOSE_TRACK_MAPPER,
    ALBUM_COMPOSE_TRACK_COMPARATOR,
    ALBUM_COMPOSE_REFRESH,
    ALBUM_COMPOSE_HEADER_REFRESH,
    ALBUM_COMPOSE_ROW,
    ALBUM_COMPOSE_ENTITY_ID,
    ARTIST_COMPOSE_CONTENT,
    ARTIST_COMPOSE_VIEW_MODEL_GETTER,
    ARTIST_COMPOSE_DATA,
    ARTIST_COMPOSE_CURRENT_DATA,
    ARTIST_COMPOSE_ENTITY_TITLE,
    ARTIST_COMPOSE_ENTITY_TYPE,
    ARTIST_COMPOSE_ENTITY_VIEWS,
    ARTIST_COMPOSE_ENTITY_RELATIONSHIPS,
    ARTIST_COMPOSE_RELATIONSHIP_ENTITIES,
    ARTIST_COMPOSE_FRAGMENT_RESUMED,
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
    ACTIVITY_THEME_MODE_FIELD,
    APP_COMPAT_THEME_MODE_FIELD,
    RADIO_SEARCH_SESSION_KIND_CLASS,
    RADIO_SEARCH_MEDIA_API_CLASS,
    RADIO_SEARCH_SCOPE_CLASS,
    RADIO_SEARCH_SESSION_KIND_FIELD,
    RADIO_SEARCH_CATALOG_KIND_FIELD,
    RADIO_SEARCH_ARTISTS_KIND_FIELD,
    RADIO_SEARCH_SCOPE_CONTEXT_METHOD,
    ALBUM_COMPOSE_ROW_KEY_FIELD,
    ALBUM_COMPOSE_KEY_ID_FIELD,
    LYRICS_PREFERENCES_TRANSLATION_GETTER,
    LYRICS_PREFERENCES_PRONUNCIATION_GETTER,
    LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD,
    LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD,
    LYRICS_PREFERENCES_STORE_GETTER,
    LYRICS_PREFERENCES_STORE_READ_METHOD,
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
    EXO_SHOULD_SKIP_TO_NEXT_ITEM_METHOD,
    EXO_PLAYER_ERROR_METHOD,
    EXO_PLAYER_FIELD,
    EXO_EVENT_HANDLER_FIELD,
    EXO_PLAYER_RETRY_METHOD,
    DEBUG_FORMAT_HOLDER_FORMAT_FIELD,
    DEBUG_FORMAT_CODECS_FIELD,
    DEBUG_FORMAT_SAMPLE_MIME_TYPE_FIELD,
    DEBUG_FORMAT_LOUDNESS_FIELD,
    DEBUG_FORMAT_CHANNEL_COUNT_FIELD,
    DEBUG_FORMAT_SAMPLE_RATE_FIELD,
    DEBUG_FORMAT_BITRATE_FIELD,
    ATMOS_LUDT_TRACK_LOUDNESS_INFO_FIELD,
    ATMOS_LUDT_LOUDNESS_FIELD,
    ATMOS_LUDT_TRUE_PEAK_FIELD,
    ATMOS_LUDT_SAMPLE_PEAK_FIELD,
    ATMOS_FORMAT_ID_FIELD,
    ATMOS_FORMAT_CODECS_FIELD,
    ATMOS_FORMAT_SAMPLE_MIME_TYPE_FIELD,
    ATMOS_FORMAT_LOUDNESS_FIELD,
    ATMOS_FORMAT_CHANNEL_COUNT_FIELD,
    ATMOS_FORMAT_SAMPLE_RATE_FIELD,
    ATMOS_FORMAT_BITRATE_FIELD,
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
    PLAYER_MODE_ENUM_CLASS,
    PLAYER_MODE_SWITCH_METHOD,
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
    ARTIST_COMPOSE_GENERIC_KIND,
    ARTIST_COMPOSE_SIMPLIFIED_KIND,
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
    CATALOG_RESPONSE_STATUS_METHOD,
    CATALOG_RESPONSE_ERRORS_METHOD,
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
    val matchVersionName: Boolean = true,
    val settingsDataCategoryHasNoSimGate: Boolean = false,
) {
    fun targets(hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        hookTargets[hookPoint].orEmpty()

    fun matches(version: AppleMusicVersion): Boolean =
        version.versionCode?.let(versionCodes::contains) == true ||
            (matchVersionName && version.versionName == versionName)
}

/**
 * Apple Music 混淆版本档案的唯一维护入口。
 *
 * 精确档案是已验证版本的快速路径和 DexKit 可信语义种子。未知版本先按“较新档案优先”
 * 尝试兼容目标，再由 DexKit 依据描述符、调用锚点和语义契约自动修复。只有 Apple 改变
 * 实际业务结构、现有契约无法唯一识别时，才需要新增或调整人工档案。
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
            stableAtmosDiagnosticHookTargets() + stablePlaybackStateHookTargets(),
    )

    private val APPLE_MUSIC_6_5_2 = AppleMusicHookProfile(
        id = "am-6.5.2-1586",
        versionName = "6.5.2",
        versionCodes = setOf(1586L),
        hookTargets = mapOf(
            // Original 1586 classes3.dex: SettingsFragment.t1()V at 0x2d98e0
            // calls LLa/c;->e(Landroid/content/Context;)Z at code-unit 0x00a1,
            // then removes KEY_CATEGORY_DATA only when false. Preserve binary case La.c;
            // do not substitute a JADX collision alias or rewritten package prefix.
            AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.settings.fragment.SettingsFragment",
                    methodName = "t1",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            // Original descriptor LLa/c;->e(Landroid/content/Context;)Z, PUBLIC STATIC,
            // code offset 0x162878: TelephonyManager.getSimState(), rejects 0 and 1.
            AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK to listOf(
                AppleMusicHookTarget(
                    className = "La.c",
                    methodName = "e",
                    parameterCount = 1,
                    parameterTypeNames = listOf("android.content.Context"),
                    returnTypeName = "boolean",
                    isStatic = true,
                ),
            ),
            // Original 1586 classes2.dex, code offset 0x47b930:
            // Lcom/apple/android/music/playback/connectivity/FuseConnectivityChecker;
            // ->isCellularAvailable()Z, PUBLIC instance, non-synthetic/non-bridge.
            // Use this binary name, not a decompiler alias or the interface declaration.
            AppleMusicHookPoint.CELLULAR_AVAILABILITY to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.playback.connectivity.FuseConnectivityChecker",
                    methodName = "isCellularAvailable",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "boolean",
                    isStatic = false,
                ),
            ),
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
                    // Raw classes2.dex evidence: the concrete buildModels method invokes these
                    // stable semantic helpers, while the Object[] bridge only invokes buildModels.
                    requiredInvokedMethodNames = listOf(
                        "buildPageTitleModel",
                        "buildBannerModel",
                        "getModelCountBuiltSoFar",
                    ),
                    requiredCallerMethodNames = listOf("buildModels"),
                ),
            ),
            // Verified from the original Apple Music 6.5.2 (1586) classes.dex.
            // z1.q and z1.i are the two text-layout classes whose constructors begin with
            // CharSequence and receive TextPaint. The older z1.k/z1.s/z1.l/z1.t names either
            // have unrelated shapes or no longer exist in this APK.
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget(
                    className = "z1.q",
                    contract = RequireComposeTextLayoutClass(
                        AppleComposeTextLayoutRole.PRIMARY,
                    ),
                ),
                AppleMusicHookTarget(
                    className = "z1.i",
                    contract = RequireComposeTextLayoutClass(
                        AppleComposeTextLayoutRole.INTRINSICS,
                    ),
                ),
            ),
            // Verified from the original Apple Music 6.5.2 (1586) classes.dex. The binary
            // class is j1$a; i1$a is the 6.5.1 identifier and must not be used as the exact
            // target for this version.
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.utils.j1\$a",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD to "c",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.2 (1586) classes.dex: z0.s0 is the
            // NeverEqualPolicy singleton (its a(Object,Object) always returns false);
            // z0.v0 and z0.t0 no longer expose a static self-typed INSTANCE field.
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.s0"),
            ),
            // Verified from the original Apple Music 6.5.2 (1586) DEX descriptor rather than
            // a decompiler display alias: C1.w.e(androidx.lifecycle.G, z0.n) returns z0.p0.
            // Its runtime state keeps the same policy field b and getValue/setValue contract
            // as the previous version's observe-as-state path.
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
                AppleMusicHookTarget(
                    className = "C1.w",
                    methodName = "e",
                    parameterCount = 2,
                    parameterTypeNames = listOf(
                        "androidx.lifecycle.G",
                        "z0.n",
                    ),
                    returnTypeName = "z0.p0",
                    isStatic = true,
                    requiredInvokedMethodNames = listOf(
                        "getValue",
                        "isInitialized",
                    ),
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
        ) + stableAtmosDiagnosticHookTargets() + atmosLoudnessMetadataHookTargets() +
            stablePlaybackStateHookTargets(),
    )

    private val APPLE_MUSIC_6_5_3 by lazy { appleMusic653Profile() }

    private fun appleMusic653Profile() = AppleMusicHookProfile(
        id = "am-6.5.3-1599",
        versionName = "6.5.3",
        versionCodes = setOf(1599L),
        hookTargets = verified653InheritedTargets() + mapOf(
            // Original 1599 classes3.dex: SettingsFragment.t1()V at 0x2d6380 still owns the
            // category build; code-unit 0x00a3 now calls LOa/c;->e(Landroid/content/Context;)Z.
            AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.settings.fragment.SettingsFragment",
                    methodName = "t1",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            // Original descriptor LOa/c;->e(Landroid/content/Context;)Z, PUBLIC STATIC,
            // code offset 0x166ed8: TelephonyManager.getSimState(), rejects 0 and 1.
            // 6.5.2's La.c#e moved to Oa.c#e; La.c was repurposed as a date formatter.
            AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK to listOf(
                AppleMusicHookTarget(
                    className = "Oa.c",
                    methodName = "e",
                    parameterCount = 1,
                    parameterTypeNames = listOf("android.content.Context"),
                    returnTypeName = "boolean",
                    isStatic = true,
                ),
            ),
            // Original 1599 classes2.dex: FuseConnectivityChecker.isCellularAvailable()Z is
            // retained unchanged from 6.5.2 (non-synthetic/non-bridge instance method).
            AppleMusicHookPoint.CELLULAR_AVAILABILITY to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.playback.connectivity.FuseConnectivityChecker",
                    methodName = "isCellularAvailable",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "boolean",
                    isStatic = false,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the playback
            // callbacks retain the same exact descriptors as 6.5.0-6.5.2.
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID to listOf(exoAudioSessionIdTarget()),
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED to listOf(
                localMediaPlayerAudioVariantChangedTarget(),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex code offset 0x665158:
            // u8.E.c0(Map)LinkedHashMap, PUBLIC STATIC, reads Locale.getDefault() and
            // Map.put("l", languageTag). The media-api cluster moved s8 -> u8.
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                AppleMusicHookTarget(
                    "u8.E", "c0", 1,
                    parameterTypeNames = listOf("java.util.Map"),
                    returnTypeName = "java.util.LinkedHashMap",
                    isStatic = true,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes3.dex code offset 0x41e590:
            // ka.a.a(RealInterceptorChain)Response, PUBLIC instance. ka.a is the app
            // cookie interceptor registered on the main OkHttp client (ka.g), and every
            // member letter of the OkHttp surface (chain request e, url a, headers c,
            // newBuilder b, builder url h / header d / build b, headers get e / values a,
            // response status d / request a / headers f) is unchanged from 6.5.2.
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION to listOf(
                contentHttpLocalizationTarget(className = "ka.a").copy(
                    parameterTypeNames = listOf("Li.f"),
                    returnTypeName = "Gi.D",
                    isStatic = false,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex code offset 0x6737ec:
            // v8.N0.d(Long dsid, String userAgent, String authorization, String storefront,
            // String id, Map query, Continuation) - same argument positions as 6.5.2's
            // t8.N0#z, and its body carries the "syllable-lyrics" path constant.
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to listOf(
                lyricsNetworkRequestTarget(className = "v8.N0", methodName = "d").copy(
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.Map", "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes3.dex code offset 0x41f91c:
            // ka.k.b(HttpUrl)List is the account cookie jar feeding
            // CookieStoreInterface cookies through Gi.l$b (obfuscated Cookie.parse);
            // the obfuscated Cookie class Gi.l keeps name field "a" and value field "b".
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to listOf(
                lyricsCookieJarTarget(className = "ka.k", methodName = "b").copy(
                    parameterTypeNames = listOf("Gi.u"),
                    returnTypeName = "java.util.List",
                    isStatic = false,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the generated
            // DataBinding package moved l7 -> n7. n7.N2.l()V reads the PlaybackItem from
            // inherited field n7.M2.g0, gates it with player.e1.i (unchanged), and applies
            // the result to the lyrics button inherited field n7.M2.Y via setEnabled.
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE to listOf(
                AppleMusicHookTarget(
                    className = "n7.N2",
                    methodName = "l",
                    parameterCount = 0,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "void",
                    isStatic = false,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD to "g0",
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD to "Y",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the action sheet
            // binding lineage (l7.e8 -> l7.f8 -> n7.h8) - n7.h8 is the concrete impl of
            // abstract n7.g8, whose layout holds five CustomTextViews plus the single
            // CollectionItemView field Y required by the runtime contract.
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                AppleMusicHookTarget("n7.h8", "l", 0),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: same lambda anchor,
            // the captured controller type moved from common.F0 to common.B0.
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                    methodName = "buildStandardSwoosh\$lambda\$35",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                        "com.apple.android.music.mediaapi.models.Recommendation",
                        "com.apple.android.music.common.B0",
                        "com.apple.android.music.mediaapi.models.MediaEntity",
                        "java.util.List",
                    ),
                    returnTypeName = "com.airbnb.epoxy.l",
                    isStatic = true,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599): common.I is the artwork lookup
            // resolver successor (6.5.2 common.L, 6.5.1 common.J); contract still
            // requires the CollectionItemView-parameter void instance method t.
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.I",
                    methodName = "t",
                    parameterCount = 1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.model.CollectionItemView"
                    ),
                    returnTypeName = "void",
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the concrete
            // buildModels override now carries (library2.H, List, List, library2.a, z6.b);
            // its body still invokes buildPageTitleModel/buildBannerModel/
            // getModelCountBuiltSoFar, and the Object[] bridge must stay excluded.
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.library2.LibraryMainContentEpoxyController",
                    methodName = "buildModels",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.library2.H",
                        "java.util.List",
                        "java.util.List",
                        "com.apple.android.music.library2.a",
                        "z6.b",
                    ),
                    returnTypeName = "void",
                    requiredInvokedMethodNames = listOf(
                        "buildPageTitleModel",
                        "buildBannerModel",
                        "getModelCountBuiltSoFar",
                    ),
                    requiredCallerMethodNames = listOf("buildModels"),
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes.dex: the Compose text
            // layout pair moved from z1.q/z1.i to z1.x (14-param primary) and
            // z1.m (3-param intrinsics); both constructors begin with CharSequence
            // and receive TextPaint, enforced by RequireComposeTextLayoutClass.
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget(
                    className = "z1.x",
                    contract = RequireComposeTextLayoutClass(
                        AppleComposeTextLayoutRole.PRIMARY,
                    ),
                ),
                AppleMusicHookTarget(
                    className = "z1.m",
                    contract = RequireComposeTextLayoutClass(
                        AppleComposeTextLayoutRole.INTRINSICS,
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599): utils.d1$a keeps the explicit
            // title formatter c(CustomTextView, String, boolean) and the unique
            // Context/AttributeSet Typeface factory j(Context, AttributeSet, R6.a, Pg.a).
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.utils.d1\$a",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD to "c",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes.dex: z0.p0 is the
            // NeverEqualPolicy singleton (static self-typed field a; a(Object,Object)
            // returns constant false). 6.5.2's z0.s0 name now holds an unrelated class.
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.p0"),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes4.dex: observeAsState moved
            // from C1.w.e to Dg.c.l(androidx.lifecycle.G, z0.m) returning z0.n0. Its body
            // still invokes getValue/isInitialized on the LiveData and the returned
            // SnapshotMutableStateImpl (z0.n1) keeps policy field b + getValue/setValue.
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
                AppleMusicHookTarget(
                    className = "Dg.c",
                    methodName = "l",
                    parameterCount = 2,
                    parameterTypeNames = listOf(
                        "androidx.lifecycle.G",
                        "z0.m",
                    ),
                    returnTypeName = "z0.n0",
                    isStatic = true,
                    requiredInvokedMethodNames = listOf(
                        "getValue",
                        "isInitialized",
                    ),
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD to "b",
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD to
                            "getValue",
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD to
                            "setValue",
                    ),
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the lyrics
            // translation/pronunciation popup is still opened by
            // player.fragment.d0#onClick, unchanged from 6.5.2.
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
            // Verified from Apple Music 6.5.3 (1599) classes2.dex: the global metadata
            // dispatcher remains player.e#onMediaMetadataChanged(Lv3/v;)V.
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.player.e",
                    methodName = "onMediaMetadataChanged",
                    parameterCount = 1,
                    returnTypeName = "void",
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599): LibraryComposeContentFragment.F0()
            // is the unique zero-parameter getter returning library2.LibraryViewModel
            // (6.5.1's A0 no longer exists; 6.5.2 never re-pinned this point).
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                    "F0",
                    0,
                    returnTypeName = "com.apple.android.music.library2.LibraryViewModel",
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599): the w8.h request executors build
            // their URL as prefix + storefront + separator + tail via A5/e.a, with the
            // storefront always the 4th argument (index 3). In 6.5.3 the storefront read
            // (u8.E.s) happens inside the suspending getter u8.E.Q AFTER v() has already
            // suspended, and no content request traverses the hooked ka.a interceptor, so
            // both the caller-side field swap and the HTTP-layer URL rewrite are dead.
            // The module rewrites index 3 per request: token requests from its
            // hle_catalog_request token, native requests from the configured content
            // storefront; the token itself is always stripped before leaving (query map
            // index differs per shape and is derived from the resolved Method signature).
            // - v8.D.d (classes2.dex 0x6693f4) "/v1/catalog/{arg3}/{arg4}", query=arg5:
            //   single-entity catalog GET held by u8.E.k (u8.E$c direct queries).
            // - v8.D.b "/v1/catalog/{arg3}?ids[i]=...": batch catalog GET, query=arg4,
            //   headers=arg5 — used by native library/browse entity batch loads and the
            //   module's localized batch queries (registered via u8.B0's w8.h.b).
            // - A5.l.d "/v1/catalog/{arg3}/search" and A5.l.c
            //   "/v1/catalog/{arg3}/search/query": catalog search, d query=arg5,
            //   c query=arg4.
            // - Ic.n.d "/v1/editorial/{arg3}/multiplex/{arg4}" and Ic.n.e
            //   "/v1/editorial/{arg3}/...": editorial browse sections, both query=arg5.
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR to listOf(
                AppleMusicHookTarget(
                    className = "v8.D",
                    methodName = "d",
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
                AppleMusicHookTarget(
                    className = "v8.D",
                    methodName = "b",
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.util.LinkedHashMap", "java.util.LinkedHashMap",
                        "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
                AppleMusicHookTarget(
                    className = "A5.l",
                    methodName = "d",
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
                AppleMusicHookTarget(
                    className = "A5.l",
                    methodName = "c",
                    parameterCount = 6,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.util.LinkedHashMap", "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
                AppleMusicHookTarget(
                    className = "Ic.n",
                    methodName = "d",
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
                AppleMusicHookTarget(
                    className = "Ic.n",
                    methodName = "e",
                    parameterCount = 7,
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "Hg.c",
                    ),
                    returnTypeName = "java.lang.Object",
                    isStatic = false,
                    allowFirstMatch = false,
                ),
            ),
            // Verified from Apple Music 6.5.3 (1599) classes2.dex code offset 0x67c174:
            // w8.d.a(Li.f)Gi.D, PUBLIC FINAL instance, implements Gi/v (the obfuscated
            // okhttp3.Interceptor). It is the AMD-retry NETWORK interceptor on the
            // amp-api/media-api OkHttp client: synchronized body, reads the chain request
            // (Li.f.e -> Gi.A) and URL (Gi.A.a -> Gi.u), calls Li.f.b(Gi.A), then retries
            // with X-Apple-AMD-Action/-Data/-M headers. Runtime evidence 2026-09-18: ART
            // monitor-contention log named the owning thread
            // "OkHttp https://amp-api.music.apple.com/..." while both contenders were in
            // Gi.D w8.d.a(Li.f) — every amp-api request executes here in its final form,
            // while the hooked ka.a interceptor only sees itunes/daap/play/se2/sync/xp
            // traffic (201012 shape trace). Hooking it yields the last-mile rewrite point
            // for browse/editorial URLs built outside the repository executors
            // (NewTabFragment / RadioFragment cached rootUrl fetches). Member letters
            // match the documented OkHttp surface (chain request e, url a, newBuilder b).
            AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR to listOf(
                contentHttpLocalizationTarget(className = "w8.d").copy(
                    parameterTypeNames = listOf("Li.f"),
                    returnTypeName = "Gi.D",
                    isStatic = false,
                ),
            ),
        ) + stableAtmosDiagnosticHookTargets() + atmosLoudnessMetadataHookTargets() +
            appleMusic653RestoredTargets(),
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
            stableAtmosDiagnosticHookTargets() + stablePlaybackStateHookTargets(),
    )

    /**
     * 1599 was checked against the original DEX, including every inherited class group.
     * Keep unchanged targets exact instead of resolving a union of older, now-repurposed
     * obfuscated classes. Overrides below repair changed owners AND downstream members.
     * This is evaluated lazily, after all older profiles have been initialized.
     */
    private fun verified653InheritedTargets(): Map<AppleMusicHookPoint, List<AppleMusicHookTarget>> =
        AppleMusicHookPoint.entries.associateWith { point ->
            APPLE_MUSIC_6_5_2.targets(point)
                .ifEmpty { APPLE_MUSIC_6_5_1.targets(point) }
                .ifEmpty { APPLE_MUSIC_6_5_0.targets(point) }
        }.filterValues { it.isNotEmpty() }

    private fun appleMusic653RestoredTargets(): Map<AppleMusicHookPoint, List<AppleMusicHookTarget>> {
        val inherited = verified653InheritedTargets()
        fun target(point: AppleMusicHookPoint) = inherited.getValue(point).single()
        return mapOf(
            // Original classes2.dex: a9.a is the RecyclerView ListAdapter, B(List)V
            // submits b9 entries; p(RecyclerView$D,I)V reads inherited A(I)Object.
            // Its list l, b9.e.b -> v3.t.d -> v3.v.{I,a,b} remain unchanged.
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT to listOf(
                target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT).copy(
                    className = "a9.a",
                    parameterTypeNames = listOf("java.util.List"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND to listOf(
                target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND).copy(
                    className = "a9.a",
                    parameterTypeNames = listOf("androidx.recyclerview.widget.RecyclerView\$D", "int"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            // NewPlayerQueueViewModel.updateHistory(List)V constructs b9.d entries.
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE to listOf(
                target(AppleMusicHookPoint.IN_APP_HISTORY_UPDATE).copy(
                    parameterTypeNames = listOf("java.util.List"),
                    returnTypeName = "void",
                    isStatic = false,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME to "b9.d",
                    ),
                ),
            ),
            // Lcom/apple/android/music/player/P; owns static a(v3.v)BaseContentItem
            // and b(v3.v)PlaybackItem. O still exists, but is now a lyrics enum.
            AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS to listOf(
                target(AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS).copy(
                    className = "com.apple.android.music.player.P",
                ),
            ),
            // Lu8/E;->v(String,Map,Continuation)Object at code_off 0x65691c creates
            // E$c, which forwards the requested URL/query to w8.h.d. B is no longer
            // this API (its first argument is Hg.c). Do not infer this from arity.
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS to listOf(
                target(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS).let { old ->
                    old.copy(runtimeMemberNames = old.runtimeMemberNames + mapOf(
                        AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD to "v",
                    ))
                },
            ),
            // Original fragment onCreateView/getRecyclerView/I2/N2: g0:n7.j5,
            // j5.Y:RecyclerView, h1:PlayerLyricsViewModel, i0:i1 (active adapter).
            // k0 is ONLY the word adapter; j0 is the line adapter. Never pin k0.
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW to listOf(
                target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).let { old ->
                    old.copy(
                        parameterTypeNames = listOf(
                            "android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle",
                        ),
                        returnTypeName = "android.view.View",
                        isStatic = false,
                        runtimeMemberNames = old.runtimeMemberNames + mapOf(
                            AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD to "g0",
                            AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD to "Y",
                            AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD to "i0",
                            AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD to "h1",
                        ),
                        contract = AllOfContract(
                            RequireFieldOfType("n7.j5", fieldName = "g0"),
                            RequireFieldOfType(
                                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
                                fieldName = "h1",
                            ),
                        ),
                    )
                },
            ),
            // buildStandardSwoosh$lambda$35 new-instance is music.i1, NOT music.l1.
            // l1 now belongs to EditorialGroupingEpoxyController (same Epoxy base).
            AppleMusicHookPoint.LISTEN_NOW_MODEL to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.i1",
                    contract = RequireFieldOfType(
                        "com.apple.android.music.listennow.ListenNowEpoxyController\$R",
                    ),
                ),
            ),
            // Original ViewDataBinding declares abstract A()V (invalidateAll).
            // n()V executes pending bindings; k0(int,databinding.i) and h0(int,Object)
            // retain registration/setVariable roles. y is now a field, not a method.
            AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES to
                inherited.getValue(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES).map { old ->
                    if (old.className == "androidx.databinding.ViewDataBinding") {
                        old.copy(runtimeMemberNames = old.runtimeMemberNames + mapOf(
                            AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD to "A",
                        ))
                    } else old
                },
            // Original AlbumPageController builds music.i / m6.c extends m6.b.
            // Playlist track models extend m6.d; its bind reads title M / subtitle N.
            // k6.* still contains classes, but no longer these row-model owners.
            AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES to
                inherited.getValue(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES).map { old ->
                    when (old.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE)) {
                        "album_header_model" -> old.copy(className = "com.apple.android.music.i")
                        "album_row_model" -> old.copy(className = "m6.b")
                        "playlist_row_model" -> old.copy(
                            className = "m6.d",
                            runtimeMemberNames = old.runtimeMemberNames + mapOf(
                                AppleMusicRuntimeMember.COLLECTION_PLAYLIST_SUBTITLE_FIELD to "N",
                            ),
                        )
                        else -> old
                    }
                },
            // BaseProfileEpoxyController.addSwipingChartItemA2 writes music.e1.L
            // from getTitle(), e1.N from the subtitle formatter, caption e1.H.
            // ArtistEpoxyController's header subclass extends music.S, title x.
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES to
                inherited.getValue(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES).map { old ->
                    when (old.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE)) {
                        "top_song_model" -> old.copy(
                            className = "com.apple.android.music.e1",
                            runtimeMemberNames = old.runtimeMemberNames + mapOf(
                                AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD to "N",
                            ),
                        )
                        "header_model" -> old.copy(className = "com.apple.android.music.S")
                        else -> old
                    }
                },
        )
    }

    private val APPLE_MUSIC_7_0_0 by lazy {
        AppleMusicHookProfile(
            id = "am-7.0.0-beta-1606",
            versionName = "7.0.0-beta",
            versionCodes = setOf(1606L),
            matchVersionName = false,
            settingsDataCategoryHasNoSimGate = true,
            hookTargets = verified700Targets(),
        )
    }

    private val APPLE_MUSIC_7_0_0_1607 by lazy {
        AppleMusicHookProfile(
            id = "am-7.0.0-beta-1607",
            versionName = "7.0.0-beta",
            versionCodes = setOf(1607L),
            matchVersionName = false,
            settingsDataCategoryHasNoSimGate = true,
            hookTargets = verified7001607Targets(),
        )
    }

    /**
     * Original 1607 base.apk SHA-256: 75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126.
     * All 135 groups / 174 targets and their member chains were checked against its four DEX files.
     * In particular, R8 merged request executors into Ef/Eg; these are binary package names,
     * not decompiler aliases. Keep the 1606 table intact for that separately verified beta.
     */
    private fun verified7001607Targets(): Map<AppleMusicHookPoint, List<AppleMusicHookTarget>> {
        val inherited = AppleMusicHookPoint.entries.associateWith(APPLE_MUSIC_7_0_0::targets)
            .filterValues { it.isNotEmpty() }
        fun target(point: AppleMusicHookPoint, index: Int = 0) = inherited.getValue(point)[index]
        return inherited + mapOf(
            // BaseActivity.onCreate/onRestart and D$a.emit now read/write k.g.b; snapshot J0 stays.
            AppleMusicHookPoint.APP_COMPAT_THEME_STATE to listOf(
                target(AppleMusicHookPoint.APP_COMPAT_THEME_STATE).copy(className = "k.g"),
            ),
            // P0.b retains /songs/{id}/syllable-lyrics; rb.k implements the original cookie jar.
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to listOf(
                target(AppleMusicHookPoint.LYRICS_NETWORK_REQUEST).copy(className = "x9.P0"),
            ),
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to listOf(
                target(AppleMusicHookPoint.LYRICS_COOKIE_JAR).copy(className = "rb.k"),
            ),
            // onCreateView installs e0; its field a captures this PlayerLyricsViewFragment.
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                target(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER).copy(
                    className = "com.apple.android.music.player.fragment.e0",
                ),
            ),
            // B2 selects r0:C / q0:V0 into p0:j1. Both keep w/x/O and d/e selection fields.
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to listOf(
                target(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER).copy(
                    className = "com.apple.android.music.player.C",
                ),
            ),
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).copy(
                    className = "com.apple.android.music.player.C",
                ),
                target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER, 1).copy(
                    className = "com.apple.android.music.player.V0",
                ),
            ),
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR to listOf(
                target(AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR).copy(
                    className = "com.apple.android.music.player.f1",
                ),
            ),
            // Original i1.C(Context,AttributeSet,g8.a,pi.a)Typeface and t(CustomTextView,String,Z)V.
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).copy(
                    className = "com.apple.android.music.utils.i1",
                ),
            ),
            // The listeners and Q.a/b now take z3.x; its title/artist/extras remain a/b/J.
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to listOf(
                target(AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER).copy(
                    parameterTypeNames = listOf("z3.x"),
                ),
            ),
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER to listOf(
                target(AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER).copy(
                    parameterTypeNames = listOf("z3.x"),
                ),
            ),
            AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS to listOf(
                target(AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS).copy(
                    className = "com.apple.android.music.player.Q",
                ),
            ),
            // The native shelf still binds five renderer families; only oa.s moved to oa.r.
            AppleMusicHookPoint.BROWSE_COMPOSE_ITEM to inherited.getValue(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM)
                .map { old -> if (old.className == "oa.s") old.copy(className = "oa.r") else old },
            AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE to listOf(
                target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).copy(returnTypeName = "z0.O0"),
            ),
            AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE to listOf(
                target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).copy(parameterTypeNames = listOf("z0.M0")),
            ),
            AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE to listOf(
                target(AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE).copy(className = "z0.O0"),
            ),
            // w9.Q initializes these executors; its request coroutines invoke Eg/Ef directly.
            // Eg.c.d/c build /search and /search/query;
            // Ef.d.e/g build /multiplex/ and /multirooms/. Storefront is still argument 3.
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR to listOf(
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 0).copy(className = "x9.D"),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 1).copy(className = "x9.D"),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 2).copy(className = "Eg.c", methodName = "d"),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 3).copy(className = "Eg.c", methodName = "c"),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 4).copy(className = "Ef.d", methodName = "e"),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 5).copy(className = "Ef.d", methodName = "g"),
            ),
            // z0.r0.toString is NeverEqualPolicy; z0.t0 is now a different class.
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY).copy(className = "z0.r0"),
            ),
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to listOf(
                target(AppleMusicHookPoint.LIBRARY_EPOXY_BUILD).copy(
                    parameterTypeNames = listOf(
                        "com.apple.android.music.library2.F", "java.util.List", "java.util.List",
                        "com.apple.android.music.library2.a", "Q7.e",
                    ),
                ),
            ),
            // LibraryComposeContentFragment.E1 calls A0.h.i; i reads LiveData.getValue,
            // h checks isInitialized and observes the lifecycle. Return type is z0.p0.
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
                target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE).copy(
                    className = "A0.h", methodName = "i", returnTypeName = "z0.p0",
                ),
            ),
            // Raw y7.m.a copies the same D7.i row/key model; four render parameter types moved.
            AppleMusicHookPoint.ALBUM_COMPOSE_ROW to listOf(
                target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).copy(
                    className = "y7.m",
                    parameterTypeNames = listOf(
                        "O0.j", "D7.i", "D7.t", "Rb.w5", "Vb.u", "Rb.v5", "Rb.x5", "int",
                        "boolean", "D7.o", "D7.p", "pi.l", "pi.p", "pi.p", "z0.m", "int",
                    ),
                ),
            ),
        )
    }

    /** Verified against all four original 1606 DEX files, SHA-256 recorded in docs. */
    private fun verified700Targets(): Map<AppleMusicHookPoint, List<AppleMusicHookTarget>> {
        val inherited = AppleMusicHookPoint.entries.associateWith(APPLE_MUSIC_6_5_3::targets)
            .filterValues { it.isNotEmpty() }
        fun target(point: AppleMusicHookPoint, index: Int = 0) = inherited.getValue(point)[index]
        // Original SettingsViewModel adds getDataCategory only after login and preferences load.
        // This settings path has no SIM gate; playback still checks SIM availability separately.
        return inherited - setOf(
            AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD,
            AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK,
        ) + mapOf(
            // 1606 binary Manifest registers MainActivity, NOT MainContentActivity.
            // The latter remains in DEX but cannot be launched (device START=-92).
            AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY to listOf(
                AppleMusicHookTarget("com.apple.android.music.common.MainActivity"),
            ),
            // Original classes2.dex MediaPlaybackService.onCreate, 0x4f3bd4:
            // getActivity(service, 0, Intent(service, MainContentActivity), IMMUTABLE).
            AppleMusicHookPoint.APPLE_MEDIA_LEGACY_ACTIVITY to listOf(
                AppleMusicHookTarget("com.apple.android.music.common.MainContentActivity"),
            ),
            AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.MediaPlaybackService", "onCreate", 0,
                    emptyList(), "void", false,
                ),
            ),
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_NEW_INTENT to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.MainActivity", "onNewIntent", 1,
                    listOf("android.content.Intent"), "void", false,
                ),
            ),
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_POST_RESUME to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.MainActivity", "onPostResume", 0,
                    emptyList(), "void", false,
                ),
            ),
            // Raw DEX 0x2cbcac: i1() returns the activity's shared MainActivityViewModel.
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_VIEW_MODEL to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.MainActivity", "i1", 0,
                    emptyList(), "com.apple.android.music.common.MainActivityViewModel", false,
                ),
            ),
            // onViewCreated starts MusicContentFragment$q collecting the non-replaying
            // playerSheetSignalChannel; q1() returns the SAME activity ViewModel.
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_CREATED to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.fragment.MusicContentFragment", "onViewCreated", 2,
                    listOf("android.view.View", "android.os.Bundle"), "void", false,
                ),
            ),
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_DESTROYED to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.fragment.MusicContentFragment", "onDestroyView", 0,
                    emptyList(), "void", false,
                ),
            ),
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_MODEL to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.fragment.MusicContentFragment", "q1", 0,
                    emptyList(), "com.apple.android.music.common.MainActivityViewModel", false,
                ),
            ),
            // Raw DEX 0x2cb9a4 -> MainActivityViewModel$d emits EXPAND (0x2cb560).
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_EXPAND to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.common.MainActivityViewModel", "requestPlayerSheetExpand", 0,
                    emptyList(), "void", false,
                ),
            ),
            // Original 1606 classes2.dex: BaseActivity.onCreate stores k.f.b in J0;
            // onRestart compares those ints and calls Activity.recreate when unequal.
            // D$a.emit(Object, Continuation) writes k.f.b from the theme preference Flow.
            // k.f.<clinit> in classes.dex initializes b to -100. These are raw DEX names.
            AppleMusicHookPoint.ACTIVITY_THEME_CREATE to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.activity.BaseActivity",
                    methodName = "onCreate", parameterCount = 1, parameterTypeNames = listOf("android.os.Bundle"),
                    returnTypeName = "void", isStatic = false,
                    runtimeMemberNames = mapOf(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD to "J0"),
                ),
            ),
            AppleMusicHookPoint.ACTIVITY_THEME_RESTART to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.activity.BaseActivity",
                    methodName = "onRestart", parameterCount = 0, parameterTypeNames = emptyList(),
                    returnTypeName = "void", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.THEME_MODE_EMIT to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.D\$a", methodName = "emit",
                    parameterCount = 2,
                    parameterTypeNames = listOf("java.lang.Object", "kotlin.coroutines.Continuation"),
                    returnTypeName = "java.lang.Object", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.APP_COMPAT_THEME_STATE to listOf(
                AppleMusicHookTarget(
                    className = "k.f",
                    runtimeMemberNames = mapOf(AppleMusicRuntimeMember.APP_COMPAT_THEME_MODE_FIELD to "b"),
                ),
            ),
            // Original 1606 DEX: Rb.E4 calls Yb.g.a for List[index], not the whole shelf.
            // These concrete renderers read MediaEntity text inside the native item composition.
            // z0.o.y -> f0 returns the current scope; q(P0) marks it used; R0.invalidate
            // schedules that composition again. Do not replace this with page/network reloads.
            AppleMusicHookPoint.BROWSE_COMPOSE_ITEM to listOf("Yb.f", "O9.j", "oa.i", "oa.s", "Yb.n").map { name ->
                AppleMusicHookTarget(
                    className = name, methodName = "a",
                    parameterCount = 11, parameterTypeNames = listOf("O0.j", "int", "java.util.List", "W8.d", "boolean", "java.lang.String", "pi.q", "pi.p", "boolean", "z0.m", "int"),
                    returnTypeName = "void", isStatic = false,
                )
            },
            AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE to listOf(
                AppleMusicHookTarget("z0.m", "y", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "z0.R0", isStatic = false),
            ),
            AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE to listOf(
                AppleMusicHookTarget("z0.m", "q", parameterCount = 1, parameterTypeNames = listOf("z0.P0"), returnTypeName = "void", isStatic = false),
            ),
            AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE to listOf(
                AppleMusicHookTarget("z0.R0", "invalidate", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "void", isStatic = false),
            ),
            AppleMusicHookPoint.SEARCH_RESULTS_MODEL_BOUND to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.search.fragments.viewpager.SearchResultsEpoxyController", "onModelBound",
                    parameterCount = 4, parameterTypeNames = listOf("com.airbnb.epoxy.J", "com.airbnb.epoxy.w", "int", "com.airbnb.epoxy.w"),
                    returnTypeName = "void", isStatic = false,
                ),
            ),
            // DEX: r.adapter:s; s.G reads the current model list. RecyclerView$f.h calls
            // RecyclerView$g.d(position, 1, null), the single-item change notification.
            AppleMusicHookPoint.SEARCH_CONTROLLER_ADAPTER to listOf(
                AppleMusicHookTarget("com.airbnb.epoxy.r", "getAdapter", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "com.airbnb.epoxy.s", isStatic = false),
            ),
            AppleMusicHookPoint.SEARCH_ADAPTER_MODEL to listOf(
                AppleMusicHookTarget("com.airbnb.epoxy.s", "G", parameterCount = 1, parameterTypeNames = listOf("int"), returnTypeName = "com.airbnb.epoxy.w", isStatic = false),
            ),
            AppleMusicHookPoint.RECYCLER_NOTIFY_ITEM_CHANGED to listOf(
                AppleMusicHookTarget("androidx.recyclerview.widget.RecyclerView\$f", "h", parameterCount = 1, parameterTypeNames = listOf("int"), returnTypeName = "void", isStatic = false),
            ),
            AppleMusicHookPoint.RADIO_STATION_CLASS to listOf(
                AppleMusicHookTarget("com.apple.android.music.mediaapi.models.RadioStation"),
            ),
            // Original constructors and descriptors, including the host's shaded CoroutineScope.
            // An isolated search session supplies candidates only; artist.station must prove identity.
            AppleMusicHookPoint.RADIO_SEARCH_SESSION to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.mediaapi.repository.MediaApiSearchSessionImpl",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.RADIO_SEARCH_SESSION_KIND_CLASS to "com.apple.android.music.mediaapi.repository.MediaApiRepository\$SearchSessionType",
                        AppleMusicRuntimeMember.RADIO_SEARCH_MEDIA_API_CLASS to "w9.a",
                        AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CLASS to "Oj.F",
                        AppleMusicRuntimeMember.RADIO_SEARCH_SESSION_KIND_FIELD to "CATALOGUE",
                        AppleMusicRuntimeMember.RADIO_SEARCH_CATALOG_KIND_FIELD to "STORE",
                        AppleMusicRuntimeMember.RADIO_SEARCH_ARTISTS_KIND_FIELD to "ARTISTS",
                        AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CONTEXT_METHOD to "getCoroutineContext",
                    ),
                ),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_START to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.mediaapi.repository.MediaApiSearchSessionImpl", "searchCatalogue",
                    parameterCount = 4, parameterTypeNames = listOf("java.lang.String", "z9.f", "com.apple.android.music.mediaapi.repository.MediaApiRepository\$CATALOGUE_TYPE", "java.util.Map"),
                    returnTypeName = "void", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_RESULT to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.mediaapi.repository.MediaApiSearchSessionImpl", "postSearchCatalogueResults",
                    parameterCount = 4, parameterTypeNames = listOf("com.apple.android.music.mediaapi.repository.MediaApiSearchResultsResponse", "z9.f", "com.apple.android.music.mediaapi.repository.MediaApiRepository\$CATALOGUE_TYPE", "com.apple.android.music.mediaapi.repository.MediaApiRepository\$SearchSessionType"),
                    returnTypeName = "void", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_CANCEL to listOf(
                AppleMusicHookTarget("com.apple.android.music.mediaapi.repository.MediaApiSearchSessionImpl", "cancelAllSearches", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "void", isStatic = false),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_RESPONSE_RESULTS to listOf(
                AppleMusicHookTarget("com.apple.android.music.mediaapi.repository.MediaApiSearchResultsResponse", "getResults", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "com.apple.android.music.mediaapi.models.internals.SearchResultsResponse", isStatic = false),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_ARTISTS to listOf(
                AppleMusicHookTarget("com.apple.android.music.mediaapi.models.internals.SearchResultsResponse", "getArtist", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "com.apple.android.music.mediaapi.models.internals.SearchResultsResponse\$SearchSectionResultResponse", isStatic = false),
            ),
            AppleMusicHookPoint.RADIO_SEARCH_ENTITIES to listOf(
                AppleMusicHookTarget("com.apple.android.music.mediaapi.models.internals.SearchResultsResponse\$SearchSectionResultResponse", "getData", parameterCount = 0, parameterTypeNames = emptyList(), returnTypeName = "java.util.List", isStatic = false),
            ),
            // Original 1606 DEX (album-row-dex.txt): collection2 copies text to D7.l.
            // refreshState republishes selection into the existing result combine. Unlike
            // refreshData, it does not switch loadTrigger away from the in-flight repository.
            // The identity comparator invalidates only an owned album whose alias changed.
            AppleMusicHookPoint.ALBUM_COMPOSE_RESUME to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.fragment.AlbumPageFragment", methodName = "onResume",
                    parameterTypeNames = listOf(), returnTypeName = "void", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.fragment.AlbumPageFragment", methodName = "r1",
                    parameterTypeNames = listOf("z0.m"), returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.fragment.AlbumPageFragment", methodName = "G1",
                    parameterTypeNames = listOf(), returnTypeName = "com.apple.android.music.collection2.viewmodel.AlbumViewModel",
                    isStatic = false,
                ),
            ),
            // Original 1606 BaseCollectionViewModel.id is the navigation identity;
            // LibraryAlbum.getId is l.* and need not expose a catalog ID.
            AppleMusicHookPoint.ALBUM_COMPOSE_PAGE_ID to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", methodName = "getId",
                    parameterTypeNames = listOf(), returnTypeName = "java.lang.String", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_CURRENT_DATA to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", methodName = "getData",
                    parameterTypeNames = listOf(), returnTypeName = "com.apple.android.music.mediaapi.models.MediaEntity",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_MAPPER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.AlbumViewModel", methodName = "transformToTrackList",
                    parameterTypeNames = listOf("com.apple.android.music.mediaapi.models.Album"), returnTypeName = "java.util.List",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", methodName = "trackDisplayItems_delegate\$lambda\$14\$lambda\$12",
                    parameterTypeNames = listOf("com.apple.android.music.mediaapi.models.MediaEntity", "com.apple.android.music.mediaapi.models.MediaEntity"), returnTypeName = "boolean",
                    isStatic = true,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", methodName = "refreshState",
                    parameterTypeNames = listOf(), returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            // Original classes2.dex: private final publishHeader()V, 1606 0x2968c4 /
            // 1607 0x296ec8. Publishes the separate header Compose state; refreshState
            // only republishes selectedItemStateLiveData for the track-list pipeline.
            AppleMusicHookPoint.ALBUM_COMPOSE_HEADER_REFRESH to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel",
                    methodName = "publishHeader", parameterTypeNames = emptyList(),
                    returnTypeName = "void", isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_ROW to listOf(
                AppleMusicHookTarget(
                    className = "y7.n", methodName = "a",
                    parameterTypeNames = listOf("O0.j", "D7.i", "D7.t", "Rb.x5", "Vb.t", "Rb.w5", "Rb.y5", "int", "boolean", "D7.o", "D7.p", "pi.l", "pi.p", "pi.p", "z0.m", "int"), returnTypeName = "void",
                    isStatic = true,
                    // D7.i.a:LD7/q; -> D7.q.a:Ljava/lang/String; (raw ID, not c's duplicate key).
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.ALBUM_COMPOSE_ROW_KEY_FIELD to "a",
                        AppleMusicRuntimeMember.ALBUM_COMPOSE_KEY_ID_FIELD to "a",
                    ),
                ),
            ),
            AppleMusicHookPoint.ALBUM_COMPOSE_ENTITY_ID to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity", methodName = "getId",
                    parameterTypeNames = listOf(), returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
            ),
            // Original 1606 DEX: profiles2 replaces the artist Epoxy surface with Compose.
            // handleProfileData rebuilds the success state and increments responseRevision;
            // replaying that exact response updates text without a network/page reload.
            AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.profiles2.ArtistFragment", methodName = "r1",
                    parameterTypeNames = listOf("z0.m"), returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.profiles2.ArtistFragment", methodName = "G1",
                    parameterTypeNames = listOf(), returnTypeName = "com.apple.android.music.profiles2.ArtistViewModel",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_DATA to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.profiles2.GenericProfileViewModel", methodName = "handleProfileData",
                    parameterTypeNames = listOf("[Lcom.apple.android.music.mediaapi.models.MediaEntity;", "com.apple.android.music.profiles2.GenericProfileViewModel\$a"), returnTypeName = "void",
                    isStatic = false,
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.ARTIST_COMPOSE_GENERIC_KIND to "GENERIC_PROFILE",
                        AppleMusicRuntimeMember.ARTIST_COMPOSE_SIMPLIFIED_KIND to "SIMPLIFIED_RESPONSE",
                    ),
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_CURRENT_DATA to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.profiles2.GenericProfileViewModel", methodName = "getData",
                    parameterTypeNames = listOf(), returnTypeName = "com.apple.android.music.mediaapi.models.MediaEntity",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TITLE to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity", methodName = "getTitle",
                    parameterTypeNames = listOf(), returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TYPE to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity", methodName = "getType",
                    parameterTypeNames = listOf(), returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_VIEWS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity", methodName = "getViews",
                    parameterTypeNames = listOf(), returnTypeName = "java.util.Map",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_RELATIONSHIPS to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.MediaEntity", methodName = "getRelationships",
                    parameterTypeNames = listOf(), returnTypeName = "java.util.Map",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_RELATIONSHIP_ENTITIES to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.mediaapi.models.internals.Relationship", methodName = "getEntities",
                    parameterTypeNames = listOf(), returnTypeName = "[Lcom.apple.android.music.mediaapi.models.MediaEntity;",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.ARTIST_COMPOSE_FRAGMENT_RESUMED to listOf(
                AppleMusicHookTarget(
                    className = "androidx.fragment.app.m", methodName = "isResumed",
                    parameterTypeNames = listOf(), returnTypeName = "boolean",
                    isStatic = false,
                ),
            ),
            // Original 1606 classes2.dex: w9.Q.q0(Map)LinkedHashMap adds the locale query.
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                target(AppleMusicHookPoint.MEDIA_API_LOCALIZATION).copy(
                    className = "w9.Q",
                    methodName = "q0",
                ),
            ),
            // Original cookie/AMD interceptors moved to rb.a / y9.d; both use (Lpk/f;)Lkk/D;.
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION to listOf(
                target(AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION).copy(
                    className = "rb.a",
                    parameterTypeNames = listOf("pk.f"),
                    returnTypeName = "kk.D",
                    // Original pk.f.e -> kk.A.c -> kk.t: d(String)String, e(int)String.
                    runtimeMemberNames = target(AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION).runtimeMemberNames +
                        (AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD to "d"),
                ),
            ),
            // Original x9.Q0.b keeps the syllable-lyrics path and seven request arguments.
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to listOf(
                target(AppleMusicHookPoint.LYRICS_NETWORK_REQUEST).copy(
                    className = "x9.Q0",
                    methodName = "b",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.Map",
                        "hi.c",
                    ),
                ),
            ),
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to listOf(
                target(AppleMusicHookPoint.LYRICS_COOKIE_JAR).copy(
                    className = "rb.l",
                    parameterTypeNames = listOf("kk.u"),
                ),
            ),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                target(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER).copy(
                    className = "com.apple.android.music.player.fragment.b0",
                ),
            ),
            // Original n1 base: w()TreeSet, x()ttml.j, O(List,int,Pair[])V; RecyclerView$f uses d/f/g.
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD to "w",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD to "x",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD to "f",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD to "d",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD to "g",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD to "O",
                    ),
                ),
                target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER, 1).copy(
                    className = "com.apple.android.music.player.Y0",
                    runtimeMemberNames = target(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER, 1).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD to "w",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD to "x",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD to "f",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD to "d",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD to "g",
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD to "O",
                    ),
                ),
            ),
            // Original ja.i0.<clinit>: k = pronunciation key, l = translation key; m/n write them.
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE to listOf(
                target(AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE).copy(
                    className = "ja.i0",
                    methodName = "n",
                    parameterTypeNames = listOf("boolean"),
                    returnTypeName = "void",
                    isStatic = true,
                ),
            ),
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE to listOf(
                target(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE).copy(
                    className = "ja.i0",
                    methodName = "m",
                    parameterTypeNames = listOf("boolean"),
                    returnTypeName = "void",
                    isStatic = true,
                ),
            ),
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION to listOf(
                target(AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION).copy(
                    methodName = "w2",
                ),
            ),
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION to listOf(
                target(AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION).copy(
                    methodName = "F2",
                ),
            ),
            // Original getRecyclerView: n0:q8.Y4 -> f0:RecyclerView. B2 selects r0/q0 into p0:n1; o1 is the ViewModel.
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW to listOf(
                target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD to "n0",
                        AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD to "f0",
                        AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD to "p0",
                        AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD to "o1",
                    ),
                    contract = AllOfContract(
                        RequireFieldOfType("q8.Y4", fieldName = "n0"),
                        RequireFieldOfType(
                            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
                            fieldName = "o1",
                        ),
                    ),
                ),
            ),
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR to listOf(
                target(AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR).copy(
                    className = "com.apple.android.music.player.i1",
                ),
            ),
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE to listOf(
                target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE).copy(
                    className = "q8.v2",
                    // Original q8.u2: n0 is PlaybackItem; f0 is player_lyrics (binding_4).
                    runtimeMemberNames = target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE).runtimeMemberNames +
                        mapOf(
                            AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD to "n0",
                            AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD to "f0",
                        ),
                ),
            ),
            AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW to listOf(
                target(AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW).copy(
                    // Original CustomTextView superclass q.B.g()V consumes Future f.
                    // q.B.f(q.B,int,float)V is a different static synthetic method.
                    runtimeMemberNames = target(AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW).runtimeMemberNames +
                        (AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD to "g"),
                ),
            ),
            // Original constructor contracts: A1.D is the primary text layout; A1.q is its intrinsics.
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                target(AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT).copy(
                    className = "A1.D",
                ),
                target(AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT, 1).copy(
                    className = "A1.q",
                ),
            ),
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).copy(
                    className = "com.apple.android.music.utils.g1",
                    runtimeMemberNames = target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD to "t",
                    ),
                ),
            ),
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                target(AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING).copy(
                    className = "q8.t7",
                ),
            ),
            // Original player.f and named PlayerListener both take Lz3/w; whose extras field is J.
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to listOf(
                target(AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER).copy(
                    className = "com.apple.android.music.player.f",
                    parameterTypeNames = listOf("z3.w"),
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER to listOf(
                target(AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD to "J",
                    ),
                    parameterTypeNames = listOf("z3.w"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE to listOf(
                target(AppleMusicHookPoint.IN_APP_HISTORY_UPDATE).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.IN_APP_HISTORY_UPDATE).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME to "fa.d",
                    ),
                ),
            ),
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT to listOf(
                target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT).copy(
                    className = "ea.a",
                    methodName = "w",
                    runtimeMemberNames = target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD to "v",
                        AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD to "J",
                    ),
                ),
            ),
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND to listOf(
                target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND).copy(
                    className = "ea.a",
                    methodName = "k",
                ),
            ),
            // Original ja.i0.g reads translation. Pronunciation has cache s and key k, read through h()->ja.E0.d(key,default).
            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS to listOf(
                target(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS).copy(
                    className = "ja.i0",
                    runtimeMemberNames = mapOf(
                        AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER to "g",
                        AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD to "s",
                        AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD to "k",
                        AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER to "h",
                        AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD to "d",
                    ),
                ),
            ),
            // Original w9.Q.F(String,Map,Continuation) is the direct URL query; f0 reads storefront t.
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS to listOf(
                target(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD to "t",
                        AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD to "F",
                    ),
                ),
            ),
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR to listOf(
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR).copy(
                    className = "x9.E",
                    methodName = "b",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "hi.c",
                    ),
                ),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 1).copy(
                    className = "x9.E",
                    methodName = "e",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.util.LinkedHashMap", "java.util.LinkedHashMap",
                        "hi.c",
                    ),
                ),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 2).copy(
                    className = "x9.u1",
                    methodName = "b",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "hi.c",
                    ),
                ),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 3).copy(
                    className = "x9.u1",
                    methodName = "a",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.util.LinkedHashMap", "hi.c",
                    ),
                ),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 4).copy(
                    className = "x9.t",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "hi.c",
                    ),
                ),
                target(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR, 5).copy(
                    className = "x9.t",
                    parameterTypeNames = listOf(
                        "java.lang.Long", "java.lang.String", "java.lang.String",
                        "java.lang.String", "java.lang.String", "java.util.LinkedHashMap",
                        "hi.c",
                    ),
                ),
            ),
            AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR to listOf(
                target(AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR).copy(
                    className = "y9.d",
                    parameterTypeNames = listOf("pk.f"),
                    returnTypeName = "kk.D",
                    runtimeMemberNames = target(AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR).runtimeMemberNames +
                        (AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD to "d"),
                ),
            ),
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY).copy(
                    className = "z0.t0",
                ),
            ),
            // Original E1 now calls inherited BaseActivityFragment.getLibraryViewModel().
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                target(AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER).copy(
                    methodName = "getLibraryViewModel",
                ),
            ),
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to listOf(
                target(AppleMusicHookPoint.LIBRARY_EPOXY_BUILD).copy(
                    parameterTypeNames = listOf(
                        "com.apple.android.music.library2.I", "java.util.List", "java.util.List",
                        "com.apple.android.music.library2.a", "Q7.e",
                    ),
                ),
            ),
            AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT to listOf(
                target(AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT).copy(
                    methodName = "E1",
                    parameterTypeNames = listOf("z0.m", "int"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            ),
            // Original library E1 invokes librarypin.u.r(G,z0.m)z0.r0; r reads getValue, q checks isInitialized.
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to listOf(
                target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE).copy(
                    className = "com.apple.android.music.library3.librarypin.u",
                    methodName = "r",
                    returnTypeName = "z0.r0",
                    requiredInvokedMethodNames = listOf("getValue"),
                ),
            ),
            // Original ViewDataBinding: o0(int,h) registration, z() invalidateAll, l0(int,Object) setVariable.
            AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES to listOf(
                target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES).copy(
                    runtimeMemberNames = target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.DATA_BINDING_REGISTRATION_METHOD to "o0",
                        AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD to "z",
                        AppleMusicRuntimeMember.DATA_BINDING_SET_VARIABLE_METHOD to "l0",
                    ),
                ),
                target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES, 1).copy(
                    className = "androidx.databinding.h",
                ),
                target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES, 2),
                target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES, 3),
                target(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES, 4),
            ),
            AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES to listOf(
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 1),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 2),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 3),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 4).copy(
                    className = "com.apple.android.music.p",
                ),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 5),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 6).copy(
                    className = "q7.b",
                ),
                target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 7).copy(
                    className = "q7.d",
                    runtimeMemberNames = target(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, 7).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.COLLECTION_PLAYLIST_SUBTITLE_FIELD to "Q",
                    ),
                ),
            ),
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES to listOf(
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES),
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 1),
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 2),
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 3),
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 4).copy(
                    className = "com.apple.android.music.u1",
                    runtimeMemberNames = target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 4).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD to "b",
                        AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD to "Q",
                    ),
                ),
                target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 5).copy(
                    className = "com.apple.android.music.i0",
                    runtimeMemberNames = target(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES, 5).runtimeMemberNames +
                        mapOf(
                        AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD to "b",
                    ),
                ),
            ),
            // Original lambda$45 constructs music.y1 and the retained ListenNow $Q callback (not lambda$42).
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to listOf(
                target(AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER).copy(
                    methodName = "buildStandardSwoosh\$lambda\$45",
                    parameterTypeNames = listOf(
                        "com.apple.android.music.listennow.ListenNowEpoxyController", "com.apple.android.music.mediaapi.models.Recommendation", "com.apple.android.music.common.T0",
                        "com.apple.android.music.mediaapi.models.MediaEntity", "java.util.List",
                    ),
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_MODEL to listOf(
                target(AppleMusicHookPoint.LISTEN_NOW_MODEL).copy(
                    className = "com.apple.android.music.y1",
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to listOf(
                target(AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER).copy(
                    className = "com.apple.android.music.common.L",
                    methodName = "s",
                ),
            ),
        )
    }


    /** 新版本档案必须放在前面，未知版本回退时优先尝试较新的目标。 */
    private val KNOWN_PROFILES = listOf(
        APPLE_MUSIC_7_0_0_1607,
        APPLE_MUSIC_7_0_0,
        APPLE_MUSIC_6_5_3,
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
            // 首参即 Media3 MediaMetadata，兼任 MEDIA3 成员名兜底载体：队列适配器 submit 类
            // 被混淆重排无法解析的版本（如 6.5.3）由该目标提供 bundle/title/artist 字段名。
            // 6.5.3 DEX 核对：PlayerSongViewFragment$PlayerListener.onMediaMetadataChanged
            // (Lv3/v;)V 仍为命名类 + 命名方法；v3.v 的 extras Bundle=I（全类唯一 Bundle），
            // title=a、artist=b（media3 声明序前两个 CharSequence），与 6.5.1/6.5.2 共享
            // SUBMIT 目标已验证的成员名一致。
            AppleMusicHookTarget(
                "com.apple.android.music.player.fragment." +
                    "PlayerSongViewFragment\$PlayerListener",
                "onMediaMetadataChanged",
                1,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD to "I",
                    AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD to "a",
                    AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD to "b",
                ),
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
            AppleMusicHookTarget(
                "com.apple.android.music.utils.AppSharedPreferences",
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER to
                        "isLyricsTranslationSelected",
                    AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_GETTER to
                        "isLyricsPronunciationSelected",
                ),
            ),
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
                    AppleMusicRuntimeMember.CATALOG_RESPONSE_STATUS_METHOD to
                        "getHttpStatusCode",
                    AppleMusicRuntimeMember.CATALOG_RESPONSE_ERRORS_METHOD to "getErrors",
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

    private fun contentHttpLocalizationTarget(
        className: String = "u8.a",
    ) = AppleMusicHookTarget(
        className = className,
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

    /**
     * Original DEX evidence for Apple Music 6.5.3 (1599): ExoMediaPlayer declares the
     * instance fields `player` (Lcom/google/android/exoplayer2/ExoPlayer;) and
     * `eventHandler` (Landroid/os/Handler;), the public instance callback
     * ->onPlayerError(Lcom/google/android/exoplayer2/ExoPlaybackException;)V, and the
     * private static decision ->shouldSkipToNextItem(Ljava/lang/Exception;I
     * Lcom/apple/android/music/playback/player/MediaPlayerContext;)Z. The retry target
     * is the public abstract ->retry()V declared directly on the ExoPlayer interface
     * held by the `player` field. 6.5.2 was additionally checked against local
     * decompiled materials by the contributing PR author.
     */
    private fun exoMediaPlayerTarget() = AppleMusicHookTarget(
        className = "com.apple.android.music.playback.player.ExoMediaPlayer",
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.EXO_SEEK_METHOD to "seekToPosition",
            AppleMusicRuntimeMember.EXO_PLAY_METHOD to "play",
            AppleMusicRuntimeMember.EXO_PAUSE_METHOD to "pause",
            AppleMusicRuntimeMember.EXO_STOP_METHOD to "stop",
            AppleMusicRuntimeMember.EXO_RELEASE_METHOD to "release",
            AppleMusicRuntimeMember.EXO_CURRENT_POSITION_METHOD to "getCurrentPosition",
            AppleMusicRuntimeMember.EXO_SHOULD_SKIP_TO_NEXT_ITEM_METHOD to
                "shouldSkipToNextItem",
            AppleMusicRuntimeMember.EXO_PLAYER_ERROR_METHOD to "onPlayerError",
            AppleMusicRuntimeMember.EXO_PLAYER_FIELD to "player",
            AppleMusicRuntimeMember.EXO_EVENT_HANDLER_FIELD to "eventHandler",
            AppleMusicRuntimeMember.EXO_PLAYER_RETRY_METHOD to "retry",
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

    /**
     * Original DEX descriptor verified on Apple Music 6.5.0 (1580), 6.5.1 (1583),
     * 6.5.2 (1586), and 6.5.3 (1599):
     *
     * `Lcom/apple/android/music/playback/player/ExoMediaPlayer;`
     * `->onPlayerStateChanged(ZI)V`.
     *
     * The first argument is ExoPlayer `playWhenReady`; the second is the raw ExoPlayer
     * state (`1=IDLE, 2=BUFFERING, 3=READY, 4=ENDED`). This exact binary callback is the
     * authoritative distinction between buffering (`true,2`) and user pause (`false,*`).
     */
    private fun stablePlaybackStateHookTargets() = mapOf(
        AppleMusicHookPoint.EXO_PLAYER_STATE_CHANGED to listOf(
            AppleMusicHookTarget(
                className = "com.apple.android.music.playback.player.ExoMediaPlayer",
                methodName = "onPlayerStateChanged",
                parameterCount = 2,
                parameterTypeNames = listOf("boolean", "int"),
                returnTypeName = "void",
                isStatic = false,
            ),
        ),
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

    /**
     * Verified from Apple Music 6.5.2 (1586) original DEX descriptors, not JADX aliases:
     *
     * - Lcom/google/android/exoplayer2/extractor/mp4/AtomParsers$LudtData;
     *   .getTrackLoudness:()F
     * - Lcom/google/android/exoplayer2/Format;
     *   .copyWithLoudness:(F)Lcom/google/android/exoplayer2/Format;
     * - Lcom/google/android/exoplayer2/Format;
     *   .copyWithManifestFormatInfo:(Lcom/google/android/exoplayer2/Format;)
     *   Lcom/google/android/exoplayer2/Format;
     */
    private fun atmosLoudnessMetadataHookTargets() = mapOf(
        AppleMusicHookPoint.ATMOS_TRACK_LOUDNESS_METADATA to listOf(
            AppleMusicHookTarget(
                className = "com.google.android.exoplayer2.extractor.mp4." +
                    "AtomParsers\$LudtData",
                methodName = "getTrackLoudness",
                parameterCount = 0,
                parameterTypeNames = emptyList(),
                returnTypeName = "float",
                isStatic = false,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ATMOS_LUDT_TRACK_LOUDNESS_INFO_FIELD to
                        "trackLoudnessInfo",
                    AppleMusicRuntimeMember.ATMOS_LUDT_LOUDNESS_FIELD to "loudness",
                    AppleMusicRuntimeMember.ATMOS_LUDT_TRUE_PEAK_FIELD to "truePeak",
                    AppleMusicRuntimeMember.ATMOS_LUDT_SAMPLE_PEAK_FIELD to "samplePeak",
                ),
            ),
        ),
        AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_LOUDNESS to listOf(
            AppleMusicHookTarget(
                className = "com.google.android.exoplayer2.Format",
                methodName = "copyWithLoudness",
                parameterCount = 1,
                parameterTypeNames = listOf("float"),
                returnTypeName = "com.google.android.exoplayer2.Format",
                isStatic = false,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ATMOS_FORMAT_ID_FIELD to "id",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_CODECS_FIELD to "codecs",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_MIME_TYPE_FIELD to
                        "sampleMimeType",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_LOUDNESS_FIELD to "loudness",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_CHANNEL_COUNT_FIELD to "channelCount",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_RATE_FIELD to "sampleRate",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_BITRATE_FIELD to "bitrate",
                ),
            ),
        ),
        AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_MANIFEST_INFO to listOf(
            AppleMusicHookTarget(
                className = "com.google.android.exoplayer2.Format",
                methodName = "copyWithManifestFormatInfo",
                parameterCount = 1,
                parameterTypeNames = listOf("com.google.android.exoplayer2.Format"),
                returnTypeName = "com.google.android.exoplayer2.Format",
                isStatic = false,
                runtimeMemberNames = mapOf(
                    AppleMusicRuntimeMember.ATMOS_FORMAT_ID_FIELD to "id",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_CODECS_FIELD to "codecs",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_MIME_TYPE_FIELD to
                        "sampleMimeType",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_LOUDNESS_FIELD to "loudness",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_CHANNEL_COUNT_FIELD to "channelCount",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_RATE_FIELD to "sampleRate",
                    AppleMusicRuntimeMember.ATMOS_FORMAT_BITRATE_FIELD to "bitrate",
                ),
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

    private fun lyricsNetworkRequestTarget(
        className: String = "t8.N0",
        methodName: String = "z",
    ) = AppleMusicHookTarget(
        className = className,
        methodName = methodName,
        allowFirstMatch = true,
    )

    private fun lyricsCookieJarTarget(
        className: String = "s8.b",
        methodName: String = "d",
    ) = AppleMusicHookTarget(
        className = className,
        methodName = methodName,
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
        val known = profileFor(version)
        // 1599 reordered the runtime namespace. Do not inject its complete target set
        // into the sparse 6.5.0-6.5.2 profiles: e.g. 1586's player.P is not the 1599 util.
        // Preserve the OLD compatibility pool within 6.5.0-6.5.2 (650 intentionally
        // borrows some 651 groups). Unknown APKs still try newest-first.
        val compatible = when (known) {
            null, APPLE_MUSIC_7_0_0_1607 -> KNOWN_PROFILES
            APPLE_MUSIC_7_0_0 -> KNOWN_PROFILES.filterNot { it === APPLE_MUSIC_7_0_0_1607 }
            APPLE_MUSIC_6_5_3 -> KNOWN_PROFILES.filterNot {
                it === APPLE_MUSIC_7_0_0 || it === APPLE_MUSIC_7_0_0_1607
            }
            else -> KNOWN_PROFILES.filterNot {
                it === APPLE_MUSIC_6_5_3 || it === APPLE_MUSIC_7_0_0 || it === APPLE_MUSIC_7_0_0_1607
            }
        }
        return (exact + compatible.flatMap { profile -> profile.targets(hookPoint) })
            .distinct()
    }
}

/**
 * 平板播放页布局相关资源名。资源名不随混淆变化，但资源 ID 会随版本重排，
 * 因此运行期通过 Resources#getIdentifier 按名解析，禁止在业务代码写死 ID。
 *
 * Verified from Apple Music 6.5.2 (1586) base.apk resource table (aapt2 dump resources):
 * - bottom_navigation (0x7f0d008e)：同一资源 id 三个变体——res/layout（手机 root_stacked
 *   全屏形态）、res/layout-w640dp-port-v13 与 res/layout-w640dp-land-v13（平板 root_flat
 *   侧板形态，root 上声明 StaticCollapsedBottomSheetBehavior）。
 * - activity_main_content_layout (0x7f0d0040) 在 XML 第 69 行 include bottom_navigation；
 *   必须在 Resources.getLayout 层覆盖该路径，不能假定每次都调用 inflate(int,...)。
 * - player_container = 0x7f0a08a2：bottom_navigation 变体内承载播放器 sheet 的
 *   CoordinatorLayout，平板变体里被 layout_constraintDimensionRatio="15:28"（
 *   string/player_aspect_ratio）约束为右侧面板；注意 music_player.xml 根布局复用了同一 ID。
 * - player_fragments_host = 0x7f0a08a4：fragment_player_main 内承载歌曲/歌词/队列
 *   子 Fragment 视图的 FrameLayout。
 * - bottom_navigation（id 0x7f0a0160）在手机 XML 中是 wrap_content + layout_gravity=center，
 *   base 元素上带 background=background_color_layer1；其父 bottom_navigation_tabs_frame
 *   （0x7f0a0163）是 match_parent 且无背景。平板上该组合会留下两侧透明区。
 * - current_player_item (0x7f0a02ce) / player_action_buttons (0x7f0a08a0) /
 *   player_controls (0x7f0a08a3)：歌词页（fragment_player_lyrics_sheet）与
 *   队列页（new_fragment_player_queue_view）内部重复的歌曲页元素。
 * - 横屏全屏几何相关（对齐第三方平板适配包对 w640dp-land 布局的改写，1586 资源表核实）：
 *   navigation_tabs_height（底栏 bar 定高）、player_container_elevation（bar 的 elevation）、
 *   shadow_height + bg_player_top_shadow（bar 顶部的渐变阴影）、separator_color（bar 顶部
 *   1dp 分隔线）、default_padding（右栏窗格边距）、current_player_margin_top（歌词页顶部
 *   margin，右栏内需清零）、enter_full_screen（歌曲页“进入全屏”按钮，横屏置 GONE）、
 *   controls / controls_tap_target（右栏歌词页隐藏的控件区，连同 current_player_item）。
 */

internal data class ResolvedAppleMusicHookClass(
    val target: AppleMusicHookTarget,
    val clazz: Class<*>,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
    val baselineClassName: String = target.className,
)

internal data class ResolvedAppleMusicHookMethod(
    val target: AppleMusicHookTarget,
    val method: Method,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
    val baselineClassName: String = target.className,
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
                ?.let { resolved ->
                    repairClass(
                        hookPoint = hookPoint,
                        resolved = resolved,
                        baselineClassName = target.className,
                        recordTrustedBaseline = true,
                    )
                }
        }
        val resolved = LinkedHashMap<String, ResolvedAppleMusicHookClass>()
        exactClasses.forEach { item ->
            resolved.putIfAbsent(item.clazz.name, item)
        }
        if (exactClasses.isNotEmpty()) {
            return resolved.values.toList()
        }

        val compatibilityClasses = AppleMusicHookProfiles.candidates(version, hookPoint)
            .filterNot { target -> exactClasses.any { it.target.className == target.className } }
            .mapNotNull { target -> loadClass(hookPoint, target, compatibilityFallback = true) }
            .map { item ->
                repairClass(
                    hookPoint = hookPoint,
                    resolved = item,
                    baselineClassName = item.baselineClassName,
                    recordTrustedBaseline = false,
                )
            }
        compatibilityClasses.forEach { item ->
            resolved.putIfAbsent(item.clazz.name, item)
        }

        val dexKitClasses = dexKitResolver?.resolveClasses(
            hookPoint = hookPoint,
            templates = AppleMusicHookProfiles.candidates(version, hookPoint).filterNot { target ->
                resolved.values.any { it.baselineClassName == target.className }
            },
            validator = { target, clazz -> classContractPasses(hookPoint, target, clazz) },
        ).orEmpty()
        dexKitClasses.forEach { item ->
            resolved.putIfAbsent(item.clazz.name, item)
        }
        if (resolved.isNotEmpty()) return resolved.values.toList()

        return resolveDexKitMethod(hookPoint)?.let { resolved ->
            listOf(
                ResolvedAppleMusicHookClass(
                    target = resolved.target,
                    clazz = resolved.method.declaringClass,
                    compatibilityFallback = true,
                    contractReason = resolved.contractReason,
                    baselineClassName = resolved.target.className,
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
            return repairClass(
                hookPoint = hookPoint,
                resolved = ResolvedAppleMusicHookClass(
                    target = target,
                    clazz = clazz,
                    compatibilityFallback = target !in exactTargets,
                    contractReason = if (target !in exactTargets) "contract_passed" else null,
                    baselineClassName = target.className,
                ),
                baselineClassName = target.className,
                recordTrustedBaseline = target in exactTargets,
            )
        }
        dexKitResolver?.resolveClasses(
            hookPoint = hookPoint,
            templates = AppleMusicHookProfiles.candidates(version, hookPoint),
            validator = { target, clazz -> classContractPasses(hookPoint, target, clazz) },
        )
            ?.firstOrNull()
            ?.let { return it }
        resolveDexKitMethod(hookPoint)?.let { resolved ->
            return ResolvedAppleMusicHookClass(
                target = resolved.target,
                clazz = resolved.method.declaringClass,
                compatibilityFallback = true,
                contractReason = resolved.contractReason,
                baselineClassName = resolved.target.className,
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
                return repairMethod(
                    hookPoint = hookPoint,
                    baselineClassName = target.className,
                    recordTrustedBaseline = target in exactTargets,
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

    /**
     * 失败可降级的方法解析：目标缺失（如新版本混淆重排后的队列适配器）返回 null 而不是
     * 抛出异常，让调用方跳过该子功能；解析失败的完整原因记入诊断日志。
     */
    fun resolveMethodOrNull(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookMethod? =
        runCatching { resolveMethod(hookPoint) }
            .onFailure {
                ProviderLogger.diagnostic(
                    "Apple Music Hook 目标解析失败，降级跳过: hook=$hookPoint, " +
                        "reason=${it.message}",
                )
            }
            .getOrNull()

    /**
     * MEDIA3 元数据成员名载体：Media3 MediaMetadata 的 bundle/title/artist 字段名随版本
     * 混淆重排，历史上由队列适配器 submit 目标携带（其条目的 metadata 即该类）。6.5.3 起
     * submit 类无法解析，改用同样以 Media3 MediaMetadata 为首参的 now-playing 监听目标
     * 兜底（其 6.5.3 目标携带二进制核对的成员名）。仅在调用期解析，两者都失败返回 null。
     */
    fun resolveMedia3MetadataTarget(): AppleMusicHookTarget? =
        resolveMethodOrNull(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT)?.target
            ?: resolveMethodOrNull(AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER)?.target

    private fun resolveDexKitMethod(
        hookPoint: AppleMusicHookPoint,
    ): ResolvedAppleMusicHookMethod? {
        val candidates = AppleMusicHookProfiles.candidates(version, hookPoint)
        if (candidates.none { it.methodName != null || it.parameterCount != null }) return null
        val resolved = dexKitResolver?.resolveMethod(
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
        ) ?: return null

        AppleMusicDexKitWatchdog.registerMethodRecovery(
            executable = resolved.method,
            invalidate = { reason ->
                dexKitResolver.rejectMethodResolution(
                    hookPoint = hookPoint,
                    templateClassName = resolved.baselineClassName,
                    method = resolved.method,
                    reason = reason,
                )
            },
            retry = {
                resolveDexKitMethod(hookPoint)?.method
            },
        )
        return resolved
    }

    fun rejectClassResolution(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookClass,
        reason: String,
    ): Boolean {
        if (!resolved.compatibilityFallback) return false
        val resolver = dexKitResolver ?: return false
        resolver.rejectClassResolution(
            hookPoint = hookPoint,
            templateClassName = resolved.baselineClassName,
            actualClassName = resolved.clazz.name,
            reason = reason,
        )
        return true
    }

    private fun repairClass(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookClass,
        baselineClassName: String,
        recordTrustedBaseline: Boolean,
    ): ResolvedAppleMusicHookClass {
        // A binary-verified exact profile outranks cross-version member baselines.
        // Otherwise a still-existing old obfuscated name can replace the new member,
        // then be persisted below as a trusted baseline for this exact version.
        val repairedTarget = if (recordTrustedBaseline) resolved.target else dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.clazz,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        if (recordTrustedBaseline) {
            dexKitResolver?.recordBaseline(
                hookPoint = hookPoint,
                target = repairedTarget,
                clazz = resolved.clazz,
                baselineClassName = baselineClassName,
            )
        }
        return resolved.copy(
            target = repairedTarget,
            baselineClassName = baselineClassName,
        )
    }

    private fun repairMethod(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookMethod,
        baselineClassName: String,
        recordTrustedBaseline: Boolean,
    ): ResolvedAppleMusicHookMethod {
        val repairedTarget = if (recordTrustedBaseline) resolved.target else dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.method.declaringClass,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        if (recordTrustedBaseline) {
            dexKitResolver?.recordMethodBaseline(
                hookPoint = hookPoint,
                target = repairedTarget,
                method = resolved.method,
                baselineClassName = baselineClassName,
            )
        }
        return resolved.copy(target = repairedTarget)
    }

    private fun loadClass(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        compatibilityFallback: Boolean,
    ): ResolvedAppleMusicHookClass? = runCatching {
        val clazz = classLookup(target.className)
        if (compatibilityFallback &&
            dexKitResolver?.isClassRejected(hookPoint, target.className, clazz.name) == true
        ) {
            return null
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
        if (contractResult is ContractResult.Rejected) return null
        ResolvedAppleMusicHookClass(
            target = target,
            clazz = clazz,
            compatibilityFallback = compatibilityFallback,
            contractReason = if (compatibilityFallback) "contract_passed" else null,
            baselineClassName = target.className,
        )
    }.getOrNull()

    private fun classContractPasses(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        clazz: Class<*>,
    ): Boolean = AppleMusicHookContracts.validate(
        HookContractContext(
            hookPoint = hookPoint,
            target = target,
            clazz = clazz,
            method = null,
            classLookup = classLookup,
            dexKitResolver = dexKitResolver,
        ),
    ) is ContractResult.Passed

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
            AppleMusicHookPoint.APPLE_MEDIA_LEGACY_ACTIVITY,
            AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE,
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_NEW_INTENT,
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_POST_RESUME,
            AppleMusicHookPoint.APPLE_MEDIA_MAIN_VIEW_MODEL,
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_CREATED,
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_DESTROYED,
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_MODEL,
            AppleMusicHookPoint.APPLE_MEDIA_PLAYER_EXPAND,
            AppleMusicHookPoint.ACTIVITY_THEME_CREATE,
            AppleMusicHookPoint.ACTIVITY_THEME_RESTART,
            AppleMusicHookPoint.THEME_MODE_EMIT,
            AppleMusicHookPoint.APP_COMPAT_THEME_STATE -> true

            AppleMusicHookPoint.MEDIA_API_LOCALIZATION ->
                Map::class.java.isAssignableFrom(method.returnType)

            AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD,
            AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK,
            AppleMusicHookPoint.CELLULAR_AVAILABILITY,
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
            AppleMusicHookPoint.EXO_MEDIA_PLAYER,
            AppleMusicHookPoint.EXO_PLAYER_STATE_CHANGED,
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
            AppleMusicHookPoint.ATMOS_TRACK_LOUDNESS_METADATA,
            AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_LOUDNESS,
            AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_MANIFEST_INFO,
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
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR,
            AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR,
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
            AppleMusicHookPoint.SEARCH_RESULTS_MODEL_BOUND,
            AppleMusicHookPoint.SEARCH_CONTROLLER_ADAPTER,
            AppleMusicHookPoint.SEARCH_ADAPTER_MODEL,
            AppleMusicHookPoint.RECYCLER_NOTIFY_ITEM_CHANGED,
            AppleMusicHookPoint.BROWSE_COMPOSE_ITEM,
            AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE,
            AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE,
            AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE,
            AppleMusicHookPoint.RADIO_STATION_CLASS,
            AppleMusicHookPoint.RADIO_SEARCH_SESSION,
            AppleMusicHookPoint.RADIO_SEARCH_START,
            AppleMusicHookPoint.RADIO_SEARCH_RESULT,
            AppleMusicHookPoint.RADIO_SEARCH_CANCEL,
            AppleMusicHookPoint.RADIO_SEARCH_RESPONSE_RESULTS,
            AppleMusicHookPoint.RADIO_SEARCH_ARTISTS,
            AppleMusicHookPoint.RADIO_SEARCH_ENTITIES,
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
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES,
            AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT,
            AppleMusicHookPoint.ARTIST_COMPOSE_VIEW_MODEL_GETTER,
            AppleMusicHookPoint.ARTIST_COMPOSE_DATA,
            AppleMusicHookPoint.ARTIST_COMPOSE_CURRENT_DATA,
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TITLE,
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TYPE,
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_VIEWS,
            AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_RELATIONSHIPS,
            AppleMusicHookPoint.ARTIST_COMPOSE_RELATIONSHIP_ENTITIES,
            AppleMusicHookPoint.ALBUM_COMPOSE_RESUME,
            AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT,
            AppleMusicHookPoint.ALBUM_COMPOSE_VIEW_MODEL_GETTER,
            AppleMusicHookPoint.ALBUM_COMPOSE_PAGE_ID,
            AppleMusicHookPoint.ALBUM_COMPOSE_CURRENT_DATA,
            AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_MAPPER,
            AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR,
            AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH,
            AppleMusicHookPoint.ALBUM_COMPOSE_HEADER_REFRESH,
            AppleMusicHookPoint.ALBUM_COMPOSE_ROW,
            AppleMusicHookPoint.ALBUM_COMPOSE_ENTITY_ID,
            AppleMusicHookPoint.ARTIST_COMPOSE_FRAGMENT_RESUMED -> true

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
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW,
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
