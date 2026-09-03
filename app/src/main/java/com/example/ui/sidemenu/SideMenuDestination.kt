package com.example.ui.sidemenu

sealed class SideMenuDestination(val title: String) {
    // DJ Tools
    object Metronome : SideMenuDestination("Metronome")
    object TapBpm : SideMenuDestination("Tap BPM")
    object KeyConverter : SideMenuDestination("Key Converter")
    object RmsMeter : SideMenuDestination("RMS Meter")
    object ClippingDetector : SideMenuDestination("Clipping Detector")
    object DynamicRangeMeter : SideMenuDestination("Dynamic Range Meter")
    object Eq : SideMenuDestination("Multipoint EQ")
    object HaasSurround : SideMenuDestination("Haas Surround")

    // Settings
    object PlaybackSettings : SideMenuDestination("Playback & Audio")
    object LibrarySettings : SideMenuDestination("Library & Storage")
    object MusicBrainzSettings : SideMenuDestination("MusicBrainz & Metadata")
    object AppearanceSettings : SideMenuDestination("Appearance")
    object GitHubUpdates : SideMenuDestination("GitHub & App Updates")
}
