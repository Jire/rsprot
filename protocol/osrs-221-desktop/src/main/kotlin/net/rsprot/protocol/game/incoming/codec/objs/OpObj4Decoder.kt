package net.rsprot.protocol.game.incoming.codec.objs

import net.rsprot.buffer.JagByteBuf
import net.rsprot.protocol.ClientProt
import net.rsprot.protocol.game.incoming.objs.OpObjEvent
import net.rsprot.protocol.game.incoming.prot.GameClientProt
import net.rsprot.protocol.message.codec.MessageDecoder

public class OpObj4Decoder : MessageDecoder<OpObjEvent> {
    override val prot: ClientProt = GameClientProt.OPOBJ4

    override fun decode(buffer: JagByteBuf): OpObjEvent {
        val controlKey = buffer.g1() == 1
        val z = buffer.g2()
        val id = buffer.g2Alt1()
        val x = buffer.g2Alt2()
        return OpObjEvent(
            id,
            x,
            z,
            controlKey,
            4,
        )
    }
}