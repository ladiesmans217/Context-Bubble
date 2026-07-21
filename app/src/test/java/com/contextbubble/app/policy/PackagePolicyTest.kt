package com.contextbubble.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePolicyTest {
    private val ownPackage = "com.contextbubble.app.play"

    @Test fun `unknown and own surfaces are protected`() {
        assertTrue(isHardBlockedPackage(null, ownPackage))
        assertTrue(isHardBlockedPackage(ownPackage, ownPackage))
    }

    @Test fun `UPI authenticator and system security surfaces are protected`() {
        assertTrue(isHardBlockedPackage("com.phonepe.app", ownPackage))
        assertTrue(isHardBlockedPackage("com.google.android.apps.authenticator2", ownPackage))
        assertTrue(isHardBlockedPackage("com.android.permissioncontroller.overlay", ownPackage))
    }

    @Test fun `ordinary apps are not hard blocked`() {
        assertFalse(isHardBlockedPackage("com.whatsapp", ownPackage))
        assertFalse(isHardBlockedPackage("com.android.chrome", ownPackage))
    }
}
