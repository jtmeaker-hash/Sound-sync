package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ExternalAppOpener {

    const val SUNO_WEB_URL = "https://suno.com/"
    const val ACE_STUDIO_WEB_URL = "https://acestudio.ai/"
    const val SPOTIFY_WEB_URL = "https://open.spotify.com/"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val SOUNDCLOUD_WEB_URL = "https://soundcloud.com/"
    const val SOUNDCLOUD_PACKAGE = "com.soundcloud.android"
    const val GITHUB_REPO_URL = "https://github.com/jtmeaker-hash/Sound-sync"
    const val GITHUB_PACKAGE = "com.github.android"

    fun openSuno(context: Context) {
        openUrlOrApp(
            context = context,
            url = SUNO_WEB_URL,
            appPackage = "com.suno.android"
        )
    }

    fun openAceStudio(context: Context) {
        openUrlOrApp(
            context = context,
            url = ACE_STUDIO_WEB_URL,
            appPackage = null
        )
    }

    fun openSpotify(context: Context) {
        openUrlOrApp(
            context = context,
            url = SPOTIFY_WEB_URL,
            appPackage = SPOTIFY_PACKAGE
        )
    }

    fun openSoundCloud(context: Context) {
        openUrlOrApp(
            context = context,
            url = SOUNDCLOUD_WEB_URL,
            appPackage = SOUNDCLOUD_PACKAGE
        )
    }

    fun openGitHub(context: Context) {
        openUrlOrApp(
            context = context,
            url = GITHUB_REPO_URL,
            appPackage = GITHUB_PACKAGE
        )
    }

    fun buildWebIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun openUrlOrApp(context: Context, url: String, appPackage: String? = null) {
        try {
            if (!appPackage.isNullOrBlank()) {
                val launchIntent = context.packageManager?.getLaunchIntentForPackage(appPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (_: Exception) {
            }
        }
    }
}
