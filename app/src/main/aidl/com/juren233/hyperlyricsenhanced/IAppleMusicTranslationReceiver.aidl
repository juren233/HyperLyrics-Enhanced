package com.juren233.hyperlyricsenhanced;

oneway interface IAppleMusicTranslationReceiver {
    void onOnlineTranslationResult(in byte[] compressedSong);
    void onOnlineTranslationCleared(String songId);
    void onOnlineTranslationSourceSwitchResult(
        long requestId,
        String songId,
        String contentType,
        String requestedSource,
        String actualSource,
        boolean successful
    );
}
