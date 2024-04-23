package net.rsprot.protocol.loginprot.incoming.pow.challenges.sha256

import net.rsprot.protocol.loginprot.incoming.pow.challenges.ChallengeMetaDataProvider

/**
 * The default SHA-256 metadata provider will return a metadata object
 * that matches what OldSchool RuneScape sends.
 * @property world the world that the client is connecting to.
 */
public class DefaultSha256MetaDataProvider(
    private val world: Int,
) : ChallengeMetaDataProvider<Sha256MetaData> {
    override fun provide(ip: Int): Sha256MetaData {
        return Sha256MetaData(world)
    }
}
