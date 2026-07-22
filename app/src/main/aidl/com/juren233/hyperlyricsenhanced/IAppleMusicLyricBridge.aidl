package com.juren233.hyperlyricsenhanced;

oneway interface IAppleMusicLyricBridge {
    void onSongChanged(in byte[] compressedSong);
    void onPlaybackStateChanged(boolean isPlaying);
    void onPositionChanged(long position);
    void onSeekTo(long position);
    void onReceiveText(String text);
    void onDisplayTranslationChanged(boolean isDisplayTranslation);
    void onDisplayRomaChanged(boolean isDisplayRoma);
}
