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
    object CarMode : SideMenuDestination("Car Mode")

    // Settings
    object ListeningStats : SideMenuDestination("Listening Statistics")
    object PlaybackSettings : SideMenuDestination("Playback & Audio")
    object LibrarySettings : SideMenuDestination("Library & Storage")
    object MetadataSettings : SideMenuDestination("Metadata & Artwork")
    object BackupRestore : SideMenuDestination("Backup & Restore")
    object AppearanceSettings : SideMenuDestination("Appearance")
    object GitHubUpdates : SideMenuDestination("GitHub & App Updates")
    object CarModeSettings : SideMenuDestination("Car Mode & Bluetooth")
}
