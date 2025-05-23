package net.rsprot.protocol.internal.game.outgoing.info.playerinfo.extendedinfo

/**
 * A class to track modifications done to a specific worn obj.
 * @param recolIndices the bitpacked indices of the source colour to overwrite.
 * @param recol1 the colour value to overwrite the source colour at the first index with.
 * @param recol2 the colour value to overwrite the source colour at the second index with.
 * @param retexIndices the bitpacked indices of the source texture to overwrite.
 * @param retex1 the texture id to overwrite the source texture at the first index with.
 * @param retex2 the texture id to overwrite the source texture at the second index with.
 * @param manWear the male body type wear model
 * @param womanWear the female body type wear model
 * @param manHead the male chathead model
 * @param womanHead the female chathead model
 */
public class ObjTypeCustomisation(
    public var recolIndices: UByte,
    public var recol1: UShort,
    public var recol2: UShort,
    public var retexIndices: UByte,
    public var retex1: UShort,
    public var retex2: UShort,
    public var manWear: UShort,
    public var womanWear: UShort,
    public var manHead: UShort,
    public var womanHead: UShort,
) {
    public constructor() : this(
        recolIndices = 0xFFu,
        recol1 = 0u,
        recol2 = 0u,
        retexIndices = 0xFFu,
        retex1 = 0u,
        retex2 = 0u,
        manWear = DEFAULT_MODEL,
        womanWear = DEFAULT_MODEL,
        manHead = DEFAULT_MODEL,
        womanHead = DEFAULT_MODEL,
    )

    public companion object {
        public const val DEFAULT_MODEL: UShort = 0xFFFFU
    }
}
