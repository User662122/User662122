package com.chesschat.app

/**
 * App-specific capture profiles to prevent black screen detection
 */
data class ChessAppProfile(
    val appName: String,
    val packageName: String,
    val captureDelay: Long = 0,  // Delay before starting capture (ms)
    val useSoftwareRenderer: Boolean = false,  // Use software rendering to avoid detection
    val frameRateLimit: Long = 1000  // Minimum ms between captures
)

object ChessAppProfiles {
    
    val profiles = listOf(
        ChessAppProfile(
            appName = "Chess.com",
            packageName = "com.chess",
            captureDelay = 1500,
            useSoftwareRenderer = true,
            frameRateLimit = 800
        ),
        ChessAppProfile(
            appName = "Lichess",
            packageName = "org.lichess.mobileapp",
            captureDelay = 1000,
            useSoftwareRenderer = false,
            frameRateLimit = 600
        ),
        ChessAppProfile(
            appName = "Chess Free",
            packageName = "uk.co.aifactory.chessfree",
            captureDelay = 500,
            useSoftwareRenderer = false,
            frameRateLimit = 1000
        ),
        ChessAppProfile(
            appName = "Miniclip Chess",
            packageName = "com.miniclip.chess",
            captureDelay = 800,
            useSoftwareRenderer = false,
            frameRateLimit = 800
        ),
        ChessAppProfile(
            appName = "Generic",
            packageName = "",
            captureDelay = 0,
            useSoftwareRenderer = false,
            frameRateLimit = 1000
        )
    )
    
    fun getProfile(packageName: String): ChessAppProfile {
        return profiles.find { it.packageName == packageName } 
            ?: profiles.last()  // Return generic profile as default
    }
}
