package com.juren233.hyperlyricsenhanced.common.lyric

object LyricMetadataKeys {
    const val APPLE_CATALOG_GENRE = "appleCatalogGenre"

    /**
     * Provider 携带的 MediaSession MEDIA_ID（小米音乐专用通道）。小米音乐车载歌词
     * 会持续把会话标题/歌手改写成歌词行，锚点侧唯一逐曲稳定且不被污染的身份是
     * mediaId；Provider 在发布歌曲时把它写入该键，Source 用它参与 TrackIdentity
     * 匹配（两侧都有 mediaId 时按 包名+mediaId 严格判定）。
     */
    const val SESSION_MEDIA_ID = "hleSessionMediaId"
    const val APPLE_PRONUNCIATION_LANGUAGES = "applePronunciationLanguages"
    const val APPLE_ORIGINAL_TITLE = "appleOriginalTitle"
    const val APPLE_ORIGINAL_ARTIST = "appleOriginalArtist"
    const val APPLE_ORIGINAL_ALBUM = "appleOriginalAlbum"
    const val APPLE_ORIGINAL_METADATA_RESOLVED = "appleOriginalMetadataResolved"
    const val APPLE_LYRICS_CACHE_SOURCE = "appleLyricsCacheSource"
    const val APPLE_NATIVE_LYRICS_CONFIRMED = "appleNativeLyricsConfirmed"
    const val APPLE_NATIVE_LYRICS_UNTIMED = "appleNativeLyricsUntimed"
    const val ONLINE_TRANSLATION_SOURCE = "onlineTranslationSource"
    const val ONLINE_TRANSLATION_MATCH_STATS = "onlineTranslationMatchStats"
    const val ONLINE_PRONUNCIATION_SOURCE = "onlinePronunciationSource"
    const val ONLINE_PRONUNCIATION_MATCH_STATS = "onlinePronunciationMatchStats"
    const val APPLE_MISSING_LYRICS_SUPPLEMENT = "appleMissingLyricsSupplement"
    const val APPLE_MISSING_LYRICS_SOURCE = "appleMissingLyricsSource"
    const val APPLE_MISSING_LYRICS_SOURCE_STATUSES = "appleMissingLyricsSourceStatuses"
    const val LUNA_BEAT_RAW_TTML = "lunaBeatRawTtml"
    const val LUNA_BEAT_HUB_ID = "lunaBeatHubId"
    const val LUNA_BEAT_TTML_SHA256 = "lunaBeatTtmlSha256"
    const val GROUP_VOCALS = "groupVocals"
    const val BACKGROUND_VOCALS_TRANSLATION = "backgroundVocalsTranslation"
    const val CONCURRENT_SECONDARY_ALIGNED_RIGHT = "concurrentSecondaryAlignedRight"
    const val OVERLAPPING_LYRICS_GROUP = "overlappingLyricsGroup"
    const val OVERLAPPING_PRIMARY_BACKING = "overlappingPrimaryBacking"
    const val OVERLAPPING_PRIMARY_BACKING_TRANSLATION =
        "overlappingPrimaryBackingTranslation"
    const val OVERLAPPING_SECONDARY_TRANSLATION = "overlappingSecondaryTranslation"
    const val OVERLAPPING_SECONDARY_BACKING = "overlappingSecondaryBacking"
    const val OVERLAPPING_SECONDARY_BACKING_TRANSLATION =
        "overlappingSecondaryBackingTranslation"
    const val INSTRUMENTAL = "instrumental"
    const val INSTRUMENTAL_TYPE = "instrumentalType"
}
