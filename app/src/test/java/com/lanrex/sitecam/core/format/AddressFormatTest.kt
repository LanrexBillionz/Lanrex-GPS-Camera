package com.lanrex.sitecam.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressFormatTest {

    private val iwo = AddressParts(
        featureName = "183",
        subThoroughfare = "183",
        thoroughfare = "Ibadan - Iwo Rd",
        locality = "Iwo",
        adminArea = "Osun",
        postalCode = "232102",
        countryName = "Nigeria",
        countryCode = "NG",
        addressLine = "183 Ibadan - Iwo Rd, Iwo 232102, Osun, Nigeria",
    )

    @Test
    fun flagEmoji() {
        assertEquals("🇳🇬", AddressFormat.flagEmoji("NG"))
        assertEquals("🇳🇬", AddressFormat.flagEmoji("ng"))
        assertEquals("🇬🇧", AddressFormat.flagEmoji("GB"))
        assertEquals("", AddressFormat.flagEmoji(null))
        assertEquals("", AddressFormat.flagEmoji("NGA"))
        assertEquals("", AddressFormat.flagEmoji("1A"))
    }

    @Test
    fun titleLineMatchesReference() {
        assertEquals("Iwo, Osun, Nigeria 🇳🇬", AddressFormat.titleLine(iwo))
    }

    @Test
    fun fullAddressMatchesReference() {
        assertEquals("183 Ibadan - Iwo Rd, Iwo, Osun 232102, Nigeria", AddressFormat.fullAddress(iwo))
    }

    @Test
    fun houseNumberFromFeatureName() {
        val parts = iwo.copy(subThoroughfare = null, featureName = "5")
        assertEquals("5 Ibadan - Iwo Rd, Iwo, Osun 232102, Nigeria", AddressFormat.fullAddress(parts))
    }

    @Test
    fun plusCodeFeatureIsKeptBeforeStreet() {
        val parts = iwo.copy(subThoroughfare = null, featureName = "J5V8+6VM", thoroughfare = "Road")
        assertEquals("J5V8+6VM, Road, Iwo, Osun 232102, Nigeria", AddressFormat.fullAddress(parts))
    }

    @Test
    fun cityFallsBackToSubAdminArea() {
        val parts = iwo.copy(locality = null, subAdminArea = "Iwo LGA")
        assertEquals("Iwo LGA, Osun, Nigeria 🇳🇬", AddressFormat.titleLine(parts))
    }

    @Test
    fun repeatedNamesAreDropped() {
        val lagos = AddressParts(locality = "Lagos", adminArea = "Lagos", countryName = "Nigeria", countryCode = "NG")
        assertEquals("Lagos, Nigeria 🇳🇬", AddressFormat.titleLine(lagos))
    }

    @Test
    fun noCountryCodeMeansNoFlag() {
        val parts = iwo.copy(countryCode = null)
        assertEquals("Iwo, Osun, Nigeria", AddressFormat.titleLine(parts))
    }

    @Test
    fun emptyAddress() {
        assertNull(AddressFormat.titleLine(AddressParts()))
        assertNull(AddressFormat.fullAddress(AddressParts()))
    }

    @Test
    fun sparsePartsUseGoogleLine() {
        val parts = AddressParts(adminArea = "Osun", countryName = "Nigeria", addressLine = "Osun State, Nigeria")
        assertEquals("Osun State, Nigeria", AddressFormat.fullAddress(parts))
    }

    @Test
    fun blankPartsAreIgnored() {
        val parts = iwo.copy(postalCode = " ", subLocality = "")
        assertEquals("183 Ibadan - Iwo Rd, Iwo, Osun, Nigeria", AddressFormat.fullAddress(parts))
    }
}
