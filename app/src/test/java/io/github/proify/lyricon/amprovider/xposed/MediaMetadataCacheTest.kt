package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMetadataCacheTest {
    @Test
    fun `catalog genres are merged and survive later queue metadata updates`() {
        val mediaId = "media-metadata-cache-catalog-genre"
        MediaMetadataCache.put(metadata(mediaId, genre = null))

        MediaMetadataCache.updateCatalogGenres(
            mediaId = mediaId,
            genres = listOf("国语流行", "音乐", "流行乐"),
        )
        MediaMetadataCache.put(metadata(mediaId, genre = null))

        val genre = MediaMetadataCache.getMetadataById(mediaId)?.genre.orEmpty()
        assertTrue(genre.contains("国语流行"))
        assertTrue(genre.contains("音乐"))
        assertTrue(genre.contains("流行乐"))
    }

    @Test
    fun `queue and catalog genres are both retained`() {
        val mediaId = "media-metadata-cache-merged-genre"
        MediaMetadataCache.put(metadata(mediaId, genre = "Pop"))
        MediaMetadataCache.updateCatalogGenres(mediaId, listOf("粤语流行"))

        assertEquals(
            "Pop, 粤语流行",
            MediaMetadataCache.getMetadataById(mediaId)?.genre,
        )
    }

    @Test
    fun `original album survives later metadata updates`() {
        val mediaId = "media-metadata-cache-original-album"
        MediaMetadataCache.put(metadata(mediaId, genre = "J-Pop"))
        MediaMetadataCache.updateOriginalMetadata(
            mediaId = mediaId,
            title = "Reply",
            artist = "kz, かぐや(cv.夏吉ゆうこ)",
            album = "超かぐや姫!",
        )
        MediaMetadataCache.put(metadata(mediaId, genre = null))

        assertEquals(
            "超かぐや姫!",
            MediaMetadataCache.getMetadataById(mediaId)?.originalAlbum,
        )
    }

    @Test
    fun `display replacement updates current album`() {
        val mediaId = "media-metadata-cache-display-album"
        MediaMetadataCache.put(metadata(mediaId, genre = "J-Pop"))

        MediaMetadataCache.updateDisplayMetadata(
            mediaId = mediaId,
            title = "Reply",
            artist = "kz, かぐや(cv.夏吉ゆうこ)",
            album = "超かぐや姫!",
        )

        assertEquals("超かぐや姫!", MediaMetadataCache.getMetadataById(mediaId)?.album)
    }

    private fun metadata(mediaId: String, genre: String?) = MediaMetadataCache.Metadata(
        id = mediaId,
        title = "Title",
        artist = "Artist",
        genre = genre,
        duration = 1L,
        queueId = 1L,
    )
}
