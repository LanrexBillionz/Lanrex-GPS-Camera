package com.lanrex.sitecam.core.format

import java.util.Locale

/**
 * The pieces of a reverse-geocoded address. Mirrors android.location.Address,
 * but plain Kotlin so it can be unit tested and stored in the database.
 */
data class AddressParts(
    val featureName: String? = null,
    val subThoroughfare: String? = null,
    val thoroughfare: String? = null,
    val subLocality: String? = null,
    val locality: String? = null,
    val subAdminArea: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
    /** Google's own one-line formatting, used only when the parts are too sparse. */
    val addressLine: String? = null,
)

object AddressFormat {

    /** "NG" -> "🇳🇬". Returns "" for anything that is not a two-letter code. */
    fun flagEmoji(countryCode: String?): String {
        val code = countryCode?.trim()?.uppercase(Locale.US) ?: return ""
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val first = Character.toChars(REGIONAL_INDICATOR_A + (code[0] - 'A'))
        val second = Character.toChars(REGIONAL_INDICATOR_A + (code[1] - 'A'))
        return String(first) + String(second)
    }

    /**
     * Line 1 of the stamp: "City, State, Country 🇳🇬", e.g. "Iwo, Osun, Nigeria 🇳🇬".
     * Returns null when nothing useful is known.
     */
    fun titleLine(parts: AddressParts): String? {
        val city = firstNonBlank(parts.locality, parts.subAdminArea, parts.subLocality)
        val names = dedupe(listOf(city, parts.adminArea.clean(), parts.countryName.clean()))
        if (names.isEmpty()) return null
        val flag = flagEmoji(parts.countryCode)
        val text = names.joinToString(", ")
        return if (flag.isEmpty()) text else "$text $flag"
    }

    /**
     * Line 2 of the stamp, e.g. "183 Ibadan - Iwo Rd, Iwo, Osun 232102, Nigeria".
     * Built from the parts so every stamp uses the same order; falls back to
     * Google's address line when the parts are too sparse.
     */
    fun fullAddress(parts: AddressParts): String? {
        val street = streetPart(parts)
        val city = firstNonBlank(parts.locality, parts.subAdminArea)
        val subLocality = parts.subLocality.clean()?.takeIf { !it.equals(city, ignoreCase = true) }
        val statePostal = listOfNotNull(parts.adminArea.clean(), parts.postalCode.clean())
            .joinToString(" ")
            .ifBlank { null }
        val pieces = dedupe(listOf(street, subLocality, city, statePostal, parts.countryName.clean()))

        val hasDetail = street != null || subLocality != null || city != null
        if (!hasDetail) {
            val line = parts.addressLine.clean()
            if (line != null) return line
        }
        if (pieces.isEmpty()) return parts.addressLine.clean()
        return pieces.joinToString(", ")
    }

    private fun streetPart(parts: AddressParts): String? {
        val street = parts.thoroughfare.clean()
        val number = parts.subThoroughfare.clean()
        val feature = parts.featureName.clean()?.takeIf { f ->
            !f.equals(street, ignoreCase = true) &&
                !f.equals(number, ignoreCase = true) &&
                !f.equals(parts.locality.clean(), ignoreCase = true) &&
                !f.equals(parts.adminArea.clean(), ignoreCase = true) &&
                !f.equals(parts.countryName.clean(), ignoreCase = true) &&
                !f.equals(parts.postalCode.clean(), ignoreCase = true) &&
                !f.equals(parts.subLocality.clean(), ignoreCase = true)
        }
        return when {
            street != null && number != null -> "$number $street"
            street != null && feature != null && feature.isHouseNumber() -> "$feature $street"
            street != null && feature != null -> "$feature, $street"
            street != null -> street
            number != null -> number
            feature != null -> feature
            else -> null
        }
    }

    private fun String.isHouseNumber(): Boolean =
        length <= 8 && first().isDigit() && all { it.isLetterOrDigit() || it == '-' || it == '/' }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstNotNullOfOrNull { it.clean() }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Drops blanks and repeated names ("Lagos, Lagos" -> "Lagos"). */
    private fun dedupe(values: List<String?>): List<String> {
        val out = ArrayList<String>()
        for (v in values) {
            val c = v.clean() ?: continue
            if (out.none { it.equals(c, ignoreCase = true) }) out.add(c)
        }
        return out
    }

    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
