/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.online.model

enum class Source {
    QM,
    NE,
    KUWO,
    KUGOU,
    LB,
    AMLL,
    ;

    /**
     * Word-timed TTML catalogs addressed by platform ID rather than searched by title.
     * Must stay in step with [com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources].
     */
    val isTtmlCatalog: Boolean get() = this == LB || this == AMLL

    companion object {
        /** For persisted source names (metadata, status records) that may be blank or unknown. */
        fun isTtmlCatalogName(name: String?): Boolean =
            entries.firstOrNull { it.name == name }?.isTtmlCatalog == true
    }
}
