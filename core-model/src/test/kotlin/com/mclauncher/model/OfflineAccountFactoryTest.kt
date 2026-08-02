package com.mclauncher.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineAccountFactoryTest {
    @Test
    fun `offline UUID is deterministic`() {
        assertEquals(
            OfflineAccountFactory.offlineUuid("Steve"),
            OfflineAccountFactory.offlineUuid("Steve")
        )
    }

    @Test
    fun `different names produce different UUIDs`() {
        assertTrue(
            OfflineAccountFactory.offlineUuid("Steve") !=
                OfflineAccountFactory.offlineUuid("Alex")
        )
    }

    @Test
    fun `validates classic minecraft username format`() {
        assertTrue(OfflineAccountFactory.validateUsername("Player_123").isSuccess)
        assertTrue(OfflineAccountFactory.validateUsername("x").isFailure)
        assertTrue(OfflineAccountFactory.validateUsername("spaces fail").isFailure)
    }

    @Test
    fun `local profile never carries an online identity`() {
        val account = OfflineAccountFactory.create("LocalTester", nowEpochMs = 1234L)

        assertEquals(AccountType.OFFLINE, account.type)
        assertEquals(account.id, account.profileId)
        assertEquals(null, account.xuid)
        assertEquals(null, account.clientId)
        assertEquals(null, account.tokenExpiresAtEpochMs)
    }
}
