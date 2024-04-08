package net.rsprot.protocol.game.outgoing.camera

import net.rsprot.protocol.message.OutgoingMessage

/**
 * Cam reset is used to clear out any camera shaking or
 * any sort of movements that might've been previously set.
 * Additionally, unlocks the camera if it has been locked in place.
 */
public data object CamReset : OutgoingMessage