package net.rsprot.protocol.game.outgoing.codec.zone.payload

import net.rsprot.buffer.JagByteBuf
import net.rsprot.protocol.ServerProt
import net.rsprot.protocol.game.outgoing.prot.GameServerProt
import net.rsprot.protocol.game.outgoing.zone.payload.ObjCount
import net.rsprot.protocol.internal.game.outgoing.codec.zone.payload.ZoneProtEncoder

public class ObjCountEncoder : ZoneProtEncoder<ObjCount> {
    override val prot: ServerProt = GameServerProt.OBJ_COUNT

    override fun encode(
        buffer: JagByteBuf,
        message: ObjCount,
    ) {
        // The function at the bottom of the OBJ_COUNT has a consistent order,
        // making it easy to identify all the properties of this packet:
        // obj_count(level, x, z, id, oldQuantity, newQuantity)
        buffer.p1Alt2(message.coordInZonePacked)
        buffer.p4Alt1(message.newQuantity)
        buffer.p2Alt2(message.id)
        buffer.p4Alt3(message.oldQuantity)
    }
}
