package com.chinar.assistant.agent.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.chinar.assistant.data.ActionResult

/**
 * Discovers installed launchable applications using Android's package visibility APIs,
 * performs fuzzy and alias name resolution, and safely launches requested apps.
 */
class AppLauncher(
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "AppLauncher"

        // Common user spoken aliases mapped to canonical search terms
        private val COMMON_ALIASES = mapOf(
            "yt" to "youtube",
            "msg" to "messages",
            "message" to "messages",
            "sms" to "messages",
            "browser" to "chrome",
            "net" to "chrome",
            "mail" to "gmail",
            "email" to "gmail",
            "photo" to "photos",
            "gallery" to "photos",
            "music" to "spotify",
            "tunes" to "music"
        )
    }

    data class AppEntry(
        val label: String,
        val packageName: String
    )

    // Cache installed launchable applications for 5 minutes (300,000ms) to avoid expensive Package Manager IPC
    private var cachedApps: List<AppEntry>? = null
    private var lastCacheTimeMs: Long = 0L
    private val cacheLock = Any()

    /**
     * Enumerates all launchable applications visible to this package.
     * Cached with a 5-minute sliding window for sub-millisecond resolution.
     */
    fun getLaunchableApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cached = cachedApps
            if (cached != null && (now - lastCacheTimeMs) < 300000L) {
                return cached
            }
        }

        val ctx = context ?: return emptyList()
        val pm = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val result = resolveInfos.mapNotNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm)?.toString()?.trim()
            val packageName = resolveInfo.activityInfo?.packageName
            if (!label.isNullOrBlank() && !packageName.isNullOrBlank()) {
                AppEntry(label, packageName)
            } else {
                null
            }
        }.distinctBy { it.packageName }

        synchronized(cacheLock) {
            cachedApps = result
            lastCacheTimeMs = now
        }
        return result
    }

    sealed class MatchResult {
        data class SingleMatch(val entry: AppEntry) : MatchResult()
        data class MultipleMatches(val candidates: List<AppEntry>) : MatchResult()
        data object NoMatch : MatchResult()
    }

    /**
     * Resolves app name against a given list of installed apps.
     */
    fun findMatchingApp(requestedName: String, apps: List<AppEntry>): MatchResult {
        val cleanQuery = requestedName.trim().lowercase()
        if (cleanQuery.isBlank()) return MatchResult.NoMatch

        val resolvedQuery = COMMON_ALIASES[cleanQuery] ?: cleanQuery

        // 1. Exact match on label
        val exactMatch = apps.firstOrNull { it.label.lowercase() == resolvedQuery }
        if (exactMatch != null) return MatchResult.SingleMatch(exactMatch)

        // 2. Exact match on package suffix or name
        val packageMatch = apps.firstOrNull { it.packageName.lowercase().endsWith(".$resolvedQuery") }
        if (packageMatch != null) return MatchResult.SingleMatch(packageMatch)

        // 3. Starts with match
        val startsWithMatches = apps.filter { it.label.lowercase().startsWith(resolvedQuery) }
        if (startsWithMatches.size == 1) return MatchResult.SingleMatch(startsWithMatches.first())

        // 4. Contains match
        val containsMatches = apps.filter { it.label.lowercase().contains(resolvedQuery) }
        if (containsMatches.size == 1) {
            return MatchResult.SingleMatch(containsMatches.first())
        } else if (containsMatches.size > 1) {
            return MatchResult.MultipleMatches(containsMatches)
        }

        return MatchResult.NoMatch
    }

    /**
     * Resolves app name and launches it.
     */
    fun launchApp(requestedName: String): ActionResult {
        val cleanQuery = requestedName.trim().lowercase()
        if (cleanQuery.isBlank()) {
            return ActionResult(false, "Please specify an app name to open.")
        }

        val apps = getLaunchableApps()
        if (apps.isEmpty()) {
            return ActionResult(false, "No launchable applications could be discovered.")
        }

        return when (val match = findMatchingApp(requestedName, apps)) {
            is MatchResult.SingleMatch -> launchPackage(match.entry)
            is MatchResult.MultipleMatches -> {
                val candidateNames = match.candidates.take(4).joinToString(", ") { "\"${it.label}\"" }
                ActionResult(
                    success = false,
                    message = "I found multiple matching apps: $candidateNames. Which one would you like to open?",
                    details = mapOf("candidates" to candidateNames, "isAmbiguous" to "true")
                )
            }
            MatchResult.NoMatch -> ActionResult(
                success = false,
                message = "Could not find an app named \"$requestedName\" on your device."
            )
        }
    }

    private fun launchPackage(entry: AppEntry): ActionResult {
        val ctx = context ?: return ActionResult(false, "Context unavailable to launch app.")
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(entry.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        if (launchIntent == null) {
            return ActionResult(false, "Could not launch ${entry.label} (no launch intent).")
        }

        return try {
            ctx.startActivity(launchIntent)
            Log.d(TAG, "[APP_LAUNCHED] Successfully launched ${entry.label} (${entry.packageName})")
            ActionResult(
                success = true,
                message = "Opened ${entry.label}.",
                details = mapOf("packageName" to entry.packageName, "label" to entry.label)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[APP_LAUNCH_ERROR] Failed launching ${entry.label}", e)
            ActionResult(false, "Failed to launch ${entry.label}: ${e.localizedMessage}")
        }
    }
}
