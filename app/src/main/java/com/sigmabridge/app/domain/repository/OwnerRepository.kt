package com.sigmabridge.app.domain.repository

/**
 * The Telegram Bot API has no built-in "who owns this bot" concept - only
 * whoever created it via BotFather knows that. Rather than add a new
 * Settings-UI section just to type in a numeric Telegram user ID, ownership
 * is claimed automatically: no owner is set by default, and
 * [claimOwnershipIfUnset] lets the first person who successfully triggers a
 * global-permission check become the permanent owner for this install. This
 * is a deliberate, minimal bootstrap - documented here rather than hidden.
 */
interface OwnerRepository {
    suspend fun getOwnerUserId(): Long?

    /** Sets the owner only if none is set yet. Returns true if this call is the one that claimed ownership. */
    suspend fun claimOwnershipIfUnset(userId: Long): Boolean
}
