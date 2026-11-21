package app.morphe.extension.reddit.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject

object FlagFilterInterceptor {

    private val BLOCKED_EMOJI_SEQUENCES = listOf(
        "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08",
        "\uD83C\uDFF3\u200D\uD83C\uDF08",
        "\uD83C\uDFF3\uFE0F\u200D\u26A7\uFE0F",
        "\uD83C\uDFF3\u200D\u26A7",
        "\uD83C\uDF08"
    )

    // Keywords matching Reddit Avatar Shop & Snoo accessory IDs/slugs/titles
    private val BLOCKED_AVATAR_SLUGS = listOf(
        "pride", "rainbow", "lgbt", "trans_flag", "transgender",
        "lesbian", "bisexual", "pansexual", "nonbinary", "asexual",
        "progress_flag", "queer", "ally_flag"
    )

    private val TARGET_PALETTES: List<List<Int>> = listOf(
        // 6-stripe classic rainbow
        listOf(
            Color.rgb(228, 3, 3),
            Color.rgb(255, 140, 0),
            Color.rgb(255, 237, 0),
            Color.rgb(0, 128, 38),
            Color.rgb(0, 77, 255),
            Color.rgb(117, 7, 135)
        ),
        // 5-stripe trans flag
        listOf(
            Color.rgb(91, 206, 250),
            Color.rgb(245, 169, 184),
            Color.rgb(255, 255, 255),
            Color.rgb(245, 169, 184),
            Color.rgb(91, 206, 250)
        ),
        // 5-stripe lesbian flag
        listOf(
            Color.rgb(213, 45, 0),
            Color.rgb(255, 154, 86),
            Color.rgb(255, 255, 255),
            Color.rgb(211, 98, 164),
            Color.rgb(163, 2, 98)
        )
    )

    @JvmStatic
    fun sanitizeText(input: CharSequence?): CharSequence? {
        if (input.isNullOrEmpty()) return input
        var text = input.toString()
        var modified = false
        for (seq in BLOCKED_EMOJI_SEQUENCES) {
            if (text.contains(seq)) {
                text = text.replace(seq, "\u25A1")
                modified = true
            }
        }
        return if (modified) text else input
    }

    /**
     * Filters GraphQL / REST JSON responses for the Avatar Shop and Snoo profiles.
     * 1. Removes pride items from shop catalog arrays.
     * 2. Strips or resets user Snoo avatar configs that equip any blocked accessory.
     */
    @JvmStatic
    fun filterNetworkPayload(rawBody: String?): String? {
        if (rawBody.isNullOrEmpty()) return rawBody
        val lower = rawBody.lowercase()
        if (BLOCKED_AVATAR_SLUGS.none { lower.contains(it) }) return rawBody

        return try {
            val trimmed = rawBody.trim()
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                scrubJsonObject(root)
                root.toString()
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                scrubJsonArray(arr)
                arr.toString()
            } else {
                rawBody
            }
        } catch (_: Exception) {
            rawBody
        }
    }

    private fun scrubJsonObject(obj: JSONObject) {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            when (value) {
                is JSONObject -> {
                    // If this object represents a user's Snoo avatar config and contains a blocked item,
                    // wipe its avatar URL and accessory list so the app falls back to the default avatar.
                    if (isSnooAvatarNode(key, value) && containsBlockedSlug(value.toString())) {
                        obj.remove(key)
                    } else {
                        scrubJsonObject(value)
                    }
                }
                is JSONArray -> scrubJsonArray(value)
            }
        }
    }

    private fun scrubJsonArray(arr: JSONArray) {
        for (i in arr.length() - 1 downTo 0) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> {
                    // Remove shop catalog items or equipped accessory entries matching blocked slugs
                    if (isShopOrAccessoryItem(item) && containsBlockedSlug(item.toString())) {
                        arr.remove(i)
                    } else {
                        scrubJsonObject(item)
                    }
                }
                is JSONArray -> scrubJsonArray(item)
            }
        }
    }

    private fun isShopOrAccessoryItem(obj: JSONObject): Boolean {
        val typeName = obj.optString("__typename", "").lowercase()
        return typeName.contains("avatar") ||
                typeName.contains("accessory") ||
                typeName.contains("outfit") ||
                typeName.contains("catalog") ||
                obj.has("accessoryId") ||
                obj.has("assetId")
    }

    private fun isSnooAvatarNode(key: String, obj: JSONObject): Boolean {
        val k = key.lowercase()
        val typeName = obj.optString("__typename", "").lowercase()
        return k.contains("snoovatar") ||
                k.contains("avatar") ||
                typeName.contains("snoovatar")
    }

    private fun containsBlockedSlug(text: String): Boolean {
        val lower = text.lowercase()
        return BLOCKED_AVATAR_SLUGS.any { lower.contains(it) }
    }

    /**
     * Checks both full-image banners/icons AND localized Snoo handheld/chest sub-regions.
     * If a Snoo avatar holds or wears a pride flag, the entire Snoo bitmap is replaced
     * with the default neutral image.
     */
    @JvmStatic
    fun filterBitmap(source: Bitmap?): Bitmap? {
        if (source == null || source.width < 8 || source.height < 8) return source

        // Check 1: Full-frame check (banners, subreddit icons, standalone flag images)
        if (checkRegionForFlag(source, 0, 0, source.width, source.height)) {
            return createDefaultBitmap(source.width, source.height)
        }

        // Check 2: Sub-region sliding scan (catches handheld flags/hearts & shirts inside Snoo avatars)
        val w = source.width
        val h = source.height
        val regions = listOf(
            // Left hand / left accessory slot (where Snoo holds flags/balloons)
            intArrayOf(0, (h * 0.25).toInt(), (w * 0.45).toInt(), (h * 0.55).toInt()),
            // Right hand / right accessory slot
            intArrayOf((w * 0.55).toInt(), (h * 0.25).toInt(), (w * 0.45).toInt(), (h * 0.55).toInt()),
            // Center torso / shirt graphic
            intArrayOf((w * 0.25).toInt(), (h * 0.45).toInt(), (w * 0.50).toInt(), (h * 0.40).toInt())
        )

        for (rect in regions) {
            val rx = rect[0].coerceIn(0, w - 4)
            val ry = rect[1].coerceIn(0, h - 4)
            val rw = rect[2].coerceAtMost(w - rx)
            val rh = rect[3].coerceAtMost(h - ry)
            if (rw >= 6 && rh >= 6 && checkRegionForFlag(source, rx, ry, rw, rh)) {
                return createDefaultBitmap(source.width, source.height)
            }
        }

        return source
    }

    private fun checkRegionForFlag(
        source: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Boolean {
        val cropped = Bitmap.createBitmap(source, x, y, width, height)
        val sampleW = 12
        val sampleH = 24
        val scaled = Bitmap.createScaledBitmap(cropped, sampleW, sampleH, true)
        if (cropped != source) cropped.recycle()

        // Scan 3 vertical columns across the region to catch tilted handheld flags/hearts
        val columnsToTest = listOf(sampleW / 4, sampleW / 2, (sampleW * 3) / 4)
        var detected = false
        for (colX in columnsToTest) {
            val verticalSamples = IntArray(sampleH) { rowY -> scaled.getPixel(colX, rowY) }
            if (matchesFlagBands(verticalSamples)) {
                detected = true
                break
            }
        }

        scaled.recycle()
        return detected
    }

    private fun matchesFlagBands(verticalSamples: IntArray): Boolean {
        for (palette in TARGET_PALETTES) {
            val bandHeight = verticalSamples.size / palette.size
            if (bandHeight < 2) continue

            var matchedBands = 0
            for (i in palette.indices) {
                val sampleY = (i * bandHeight) + (bandHeight / 2)
                if (sampleY < verticalSamples.size &&
                    colorDistance(verticalSamples[sampleY], palette[i]) < 55.0
                ) {
                    matchedBands++
                }
            }
            if (matchedBands >= (palette.size * 0.8).toInt()) {
                return true
            }
        }
        return false
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        if (Color.alpha(c1) < 128) return 999.0
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun createDefaultBitmap(width: Int, height: Int): Bitmap {
        val fallback = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fallback)
        val paint = Paint().apply {
            color = Color.rgb(42, 42, 46)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return fallback
    }
}
