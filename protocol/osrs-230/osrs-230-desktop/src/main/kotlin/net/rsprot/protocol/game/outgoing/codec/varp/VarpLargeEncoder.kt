package net.rsprot.protocol.game.outgoing.codec.varp

import net.rsprot.buffer.JagByteBuf
import net.rsprot.crypto.cipher.StreamCipher
import net.rsprot.protocol.ServerProt
import net.rsprot.protocol.game.outgoing.prot.GameServerProt
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.message.codec.MessageEncoder

public class VarpLargeEncoder : MessageEncoder<VarpLarge> {
    override val prot: ServerProt = GameServerProt.VARP_LARGE

    override fun encode(
        streamCipher: StreamCipher,
        buffer: JagByteBuf,
        message: VarpLarge,
    ) {
        buffer.p2(message.id)
        buffer.p4Alt3(message.value)
    }
}
