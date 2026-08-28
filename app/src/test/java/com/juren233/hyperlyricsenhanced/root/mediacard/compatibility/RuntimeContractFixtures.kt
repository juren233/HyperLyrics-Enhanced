/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.compatibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

private val DEX_CLASS_DESCRIPTOR = Regex("L[^;]+;")

enum class ContractRequirement {
    REQUIRED,
    OPTIONAL,
}

data class RuntimeIdentifierFixture(
    val classDescriptor: String,
    val memberName: String,
    val memberDescriptor: String,
    val requirement: ContractRequirement,
)

data class CapabilityFixture(
    val name: String,
    val available: Boolean,
    val requirement: ContractRequirement,
)

data class RuntimeProfileFixture(
    val profileId: String,
    val classLoaderId: String,
    val identifiers: List<RuntimeIdentifierFixture>,
    val capabilities: List<CapabilityFixture>,
) {
    fun cacheNamespace(): String = "$profileId@$classLoaderId"
}

object RuntimeContractAssertions {
    fun assertValidProfile(profile: RuntimeProfileFixture) {
        assertTrue("profile id must not be blank", profile.profileId.isNotBlank())
        assertTrue("class loader id must not be blank", profile.classLoaderId.isNotBlank())
        assertTrue("profile must contain at least one identifier", profile.identifiers.isNotEmpty())

        val duplicateKeys = profile.identifiers
            .groupingBy { Triple(it.classDescriptor, it.memberName, it.memberDescriptor) }
            .eachCount()
            .filterValues { it > 1 }
        assertTrue("duplicate runtime identifier: $duplicateKeys", duplicateKeys.isEmpty())

        profile.identifiers.forEach { identifier ->
            assertTrue(
                "invalid class descriptor: ${identifier.classDescriptor}",
                DEX_CLASS_DESCRIPTOR.matches(identifier.classDescriptor),
            )
            assertTrue("member name must not be blank", identifier.memberName.isNotBlank())
            assertTrue("member descriptor must not be blank", identifier.memberDescriptor.isNotBlank())
        }
    }

    fun assertRequiredCapabilitiesAvailable(profile: RuntimeProfileFixture) {
        val missing = profile.capabilities
            .filter { it.requirement == ContractRequirement.REQUIRED && !it.available }
            .map(CapabilityFixture::name)
        assertTrue("required capabilities unavailable: $missing", missing.isEmpty())
    }

    fun assertOptionalCapabilityMayBeUnavailable(profile: RuntimeProfileFixture, name: String) {
        val capability = profile.capabilities.firstOrNull { it.name == name }
        assertNotNull("missing optional capability fixture: $name", capability)
        assertTrue(
            "capability must be optional: $name",
            capability!!.requirement == ContractRequirement.OPTIONAL,
        )
        assertFalse(
            "optional capability fixture should exercise the unavailable path: $name",
            capability.available,
        )
    }
}
