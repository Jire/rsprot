package net.rsprot.protocol.game.outgoing.info.playerinfo

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import net.rsprot.buffer.bitbuffer.BitBuf
import net.rsprot.buffer.bitbuffer.UnsafeLongBackedBitBuf
import net.rsprot.buffer.bitbuffer.toBitBuf
import net.rsprot.buffer.extensions.toJagByteBuf
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.info.ByteBufRecycler
import net.rsprot.protocol.game.outgoing.info.ObserverExtendedInfoFlags
import net.rsprot.protocol.game.outgoing.info.exceptions.InfoProcessException
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerInfoProtocol.Companion.PROTOCOL_CAPACITY
import net.rsprot.protocol.game.outgoing.info.playerinfo.util.CellOpcodes
import net.rsprot.protocol.game.outgoing.info.util.Avatar
import net.rsprot.protocol.game.outgoing.info.util.BuildArea
import net.rsprot.protocol.game.outgoing.info.util.ReferencePooledObject
import net.rsprot.protocol.internal.game.outgoing.info.CoordGrid
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.abs

/**
 * An implementation of the player info packet.
 * This class is responsible for tracking and building the packets each cycle.
 * This class utilizes [ReferencePooledObject], meaning instances of it will be pooled
 * and re-used as needed, as the data stored within them is relatively memory-heavy.
 *
 * @param protocol the repository of all the [PlayerInfo] objects,
 * as well as a source global information about everyone in the game.
 * As the packet is responsible for tracking everyone in the game,
 * we need to provide access to this.
 * @param localIndex the index of this local player. The index corresponds to the player's slot
 * in the world. The index will not change throughout the lifespan of a player,
 * but can change within allocations in the reference pool.
 * @param allocator the [ByteBuf] allocator responsible for allocating the primary buffer
 * the is written out to the pipeline, as well as any intermediate buffers used by extended
 * info blocks. The allocator should ideally be pooled, as we acquire a new instance with each
 * cycle. This is because there isn't necessarily a guarantee that Netty threads have fully
 * written the information out to the network by the time the next cycle comes along and starts
 * writing into this buffer. A direct implementation is also preferred, as this avoids unnecessary
 * copying from and to the heap.
 * @param oldSchoolClientType the client on which the player is logging into. This is utilized
 * to determine what encoders to use for extended info blocks.
 */
@Suppress("DuplicatedCode", "ReplaceUntilWithRangeUntil")
public class PlayerInfo internal constructor(
    private val protocol: PlayerInfoProtocol,
    internal var localIndex: Int,
    internal val allocator: ByteBufAllocator,
    private var oldSchoolClientType: OldSchoolClientType,
    public val avatar: PlayerAvatar,
    private val recycler: ByteBufRecycler,
    private val globalLowResolutionPositionRepository: GlobalLowResolutionPositionRepository,
) : ReferencePooledObject {
    /**
     * Low resolution indices are tracked together with [lowResolutionCount].
     * Whenever a player enters the low resolution view, their index
     * is added into this [lowResolutionIndices] array, and the [lowResolutionCount]
     * is incremented by one.
     * At the end of each cycle, the [lowResolutionIndices] are rebuilt to sort the indices.
     */
    private val lowResolutionIndices: ShortArray = ShortArray(PROTOCOL_CAPACITY)

    /**
     * The number of players in low resolution according to the protocol.
     */
    private var lowResolutionCount: Int = 0

    /**
     * The tracked high resolution players by their indices.
     * If a player enters our high resolution, the bit at their index is set to true.
     * We do not need to use references to players as we can then refer to the [PlayerInfoRepository]
     * to find the actual [PlayerInfo] implementation.
     */
    private val highResolutionPlayers: LongArray = LongArray(PROTOCOL_CAPACITY ushr 6)

    /**
     * High resolution indices are tracked together with [highResolutionCount].
     * Whenever an external player enters the high resolution view, their index
     * is added into this [highResolutionIndices] array, and the [highResolutionCount]
     * is incremented by one.
     * At the end of each cycle, the [highResolutionIndices] are rebuilt to sort the indices.
     */
    private val highResolutionIndices: ShortArray = ShortArray(PROTOCOL_CAPACITY)

    /**
     * A bitset of high resolution players for whom we have written extended info.
     * In the case of someone being added to high resolution, but due to our buffer being too
     * full to actually write their extended info, we need to try again the next tick (and so on)
     * until we finally succeed in synchronizing them. Without this, one could end up with invisible
     * players.
     */
    private val highResolutionExtendedInfoTrackedPlayers: LongArray = LongArray(PROTOCOL_CAPACITY ushr 6)

    /**
     * The number of players in high resolution according to the protocol.
     */
    private var highResolutionCount: Int = 0

    /**
     * The extended info indices contain pointers to all the players for whom we need to
     * write an extended info block. We do this rather than directly writing them as this
     * improves CPU cache locality and allows us to batch extended info blocks together.
     */
    private val extendedInfoIndices: ShortArray = ShortArray(PROTOCOL_CAPACITY)

    /**
     * The number of players for whom we need to write extended info blocks this cycle.
     */
    private var extendedInfoCount: Int = 0

    /**
     * The flags indicating the status of the players in the previous and current cycles.
     * This is used to categorize players who are 'stationary', which implies they did not
     * move, nor did they have any extended info blocks written for them. By batching
     * players up this way, the protocol is able to skip a larger number of players
     * with each skip block, as players are far more likely to be in the same state
     * as they were in the last cycle.
     */
    private val stationary = ByteArray(PROTOCOL_CAPACITY)

    /**
     * The observer info flags are used for us to track extended info blocks which weren't necessarily
     * flagged on the target player. This can happen during the transitioning from low resolution
     * to high resolution, in which case appearance, move speed and face pathingentity may be transmitted,
     * despite not having been flagged. Additionally, some extended info blocks, such as hits and tinting,
     * will sometimes be observer-dependent. This means each observer will receive a different variant
     * of the extended info buffer. A simple example of this is the red circle hitmark ironmen will
     * see on NPCs whenever they attack a NPC that has already received damage from another player.
     * Only the ironman will receive information about that hitmark in this case, and no one else.
     */
    internal val observerExtendedInfoFlags: ObserverExtendedInfoFlags = ObserverExtendedInfoFlags(PROTOCOL_CAPACITY)

    /**
     * High resolution bit buffers are cached to avoid small computations for each observer,
     * and it allows us to reduce the number of [BitBuf.pBits] calls, which are quite expensive.
     * This implementation will store all the information inside a 'long' primitive, as the maximum
     * data size will always fit in under 50 bits.
     */
    private var highResMovementBuffer: UnsafeLongBackedBitBuf? = null

    /**
     * The buffer into which all the information is written in this cycle.
     * It should be noted that this buffer is constantly changing, as we reallocate
     * a new buffer instance through the [allocator] each cycle. This is to ensure that
     * we do not start overwriting a buffer before it has been fully written into the pipeline.
     * Thus, a pooled [allocator] implementation should be preferred to avoid expensive re-allocations.
     */
    private var buffer: ByteBuf? = null

    /**
     * The exception that was caught during the processing of this player's playerinfo packet.
     * This exception will be propagated further during the [toPacket] function call,
     * allowing the server to handle it properly at a per-player basis.
     */
    @Volatile
    internal var exception: Exception? = null

    /**
     * The entire build area of this world - this effectively caps what we can see
     * to be within this block of land. Anything outside will be excluded.
     */
    private var buildArea: BuildArea = BuildArea.INVALID

    /**
     * Returns the backing buffer for this cycle.
     * @throws IllegalStateException if the buffer has not been allocated yet.
     */
    @Throws(IllegalStateException::class)
    private fun backingBuffer(): ByteBuf = checkNotNull(buffer)

    override fun isDestroyed(): Boolean = this.exception != null

    /**
     * Updates the build area of this player info object.
     * This will ensure that no players outside of this box will be
     * added to high resolution view.
     * @param buildArea the build area to assign.
     */
    public fun updateBuildArea(buildArea: BuildArea) {
        if (isDestroyed()) return
        this.buildArea = buildArea
    }

    /**
     * Updates the build area of this player info object.
     * This will ensure that no players outside of this box will be
     * added to high resolution view.
     * @param zoneX the south-western zone x coordinate of the build area
     * @param zoneZ the south-western zone z coordinate of the build area
     * @param widthInZones the build area width in zones (typically 13, meaning 104 tiles)
     * @param heightInZones the build area height in zones (typically 13, meaning 104 tiles)
     */
    @JvmOverloads
    public fun updateBuildArea(
        zoneX: Int,
        zoneZ: Int,
        widthInZones: Int = BuildArea.DEFAULT_BUILD_AREA_SIZE,
        heightInZones: Int = BuildArea.DEFAULT_BUILD_AREA_SIZE,
    ) {
        if (isDestroyed()) return
        this.buildArea = BuildArea(zoneX, zoneZ, widthInZones, heightInZones)
    }

    /**
     * Gets the high resolution indices in a new arraylist of integers.
     * The list is initialized to an initial capacity equal to the high resolution player index count.
     * @return the newly created arraylist of indices
     */
    public fun getHighResolutionIndices(): ArrayList<Int> {
        if (isDestroyed()) return ArrayList(0)
        val collection = ArrayList<Int>(highResolutionCount)
        for (i in 0..<highResolutionCount) {
            val index = highResolutionIndices[i].toInt()
            collection.add(index)
        }
        return collection
    }

    /**
     * Appends the high resolution indices to the provided [collection]. This can be used to determine which players the
     * player is currently seeing in the client.
     * @param collection the mutable collection of integer indices to append the indices into.
     * @return the provided [collection] to chaining.
     */
    public fun <T> appendHighResolutionIndices(collection: T): T where T : MutableCollection<Int> {
        if (isDestroyed()) return collection
        for (i in 0..<highResolutionCount) {
            val index = highResolutionIndices[i].toInt()
            collection.add(index)
        }
        return collection
    }

    /**
     * Turns the player info object into a wrapped packet.
     * This is necessary because the encoder itself is only triggered in Netty, and it is possible
     * that the buffer has already been replaced with a new variant before it gets to that stage.
     * @return thread-safe player info packet class, wrapping the pre-built buffer.
     * @throws InfoProcessException if there was an exception during the computation of player info
     * for this specific playerinfo object,
     */
    public fun toPacket(): PlayerInfoPacket {
        val exception = this.exception
        if (exception != null) {
            throw InfoProcessException(
                "Exception occurred during player info processing for index $localIndex",
                exception,
            )
        }
        return PlayerInfoPacket(backingBuffer())
    }

    /**
     * Updates the current known coordinate of the given [Avatar].
     * This function must be called on each avatar before player info is computed.
     * @param level the current height level of the avatar.
     * @param x the x coordinate of the avatar.
     * @param z the z coordinate of the avatar (this is commonly referred to as 'y' coordinate).
     * @throws IllegalArgumentException if [level] is not in range of 0 until 4, or [x]/[z] are
     * not in range of 0 until 16384.
     */
    @Throws(IllegalArgumentException::class)
    public fun updateCoord(
        level: Int,
        x: Int,
        z: Int,
    ) {
        if (isDestroyed()) return
        this.avatar.updateCoord(level, x, z)
    }

    /**
     * Checks whether the player at [index] is currently among high resolution players.
     * @param index the index of the player to check.
     */
    private fun isHighResolution(index: Int): Boolean {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        return this.highResolutionPlayers[longIndex] and bit != 0L
    }

    /**
     * Marks the player at index [index] as being in high resolution.
     * @param index the index of the player to mark as high resolution.
     */
    private fun setHighResolution(index: Int) {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        val cur = this.highResolutionPlayers[longIndex]
        this.highResolutionPlayers[longIndex] = cur or bit
    }

    /**
     * Marks the player at index [index] as being in low resolution.
     * @param index the index of the player to mark as low resolution.
     */
    private fun unsetHighResolution(index: Int) {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        val cur = this.highResolutionPlayers[longIndex]
        this.highResolutionPlayers[longIndex] = cur and bit.inv()
    }

    /**
     * Checks whether the player at [index] is currently among high resolution extended info players.
     * @param index the index of the player to check.
     */
    private fun isHighResolutionExtendedInfoTracked(index: Int): Boolean {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        return this.highResolutionExtendedInfoTrackedPlayers[longIndex] and bit != 0L
    }

    /**
     * Marks the player at index [index] as being in high resolution extended info.
     * @param index the index of the player to mark as high resolution.
     */
    private fun setHighResolutionExtendedInfoTracked(index: Int) {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        val cur = this.highResolutionExtendedInfoTrackedPlayers[longIndex]
        this.highResolutionExtendedInfoTrackedPlayers[longIndex] = cur or bit
    }

    /**
     * Marks the player at index [index] as being in low resolution extended info.
     * @param index the index of the player to mark as low resolution.
     */
    private fun unsetHighResolutionExtendedInfoTracked(index: Int) {
        val longIndex = index ushr 6
        val bit = 1L shl (index and 0x3F)
        val cur = this.highResolutionExtendedInfoTrackedPlayers[longIndex]
        this.highResolutionExtendedInfoTrackedPlayers[longIndex] = cur and bit.inv()
    }

    /**
     * Handles initializing absolute player positions.
     * @param byteBuf the buffer into which the information will be written.
     */
    public fun handleAbsolutePlayerPositions(byteBuf: ByteBuf) {
        if (isDestroyed()) return
        check(avatar.currentCoord != CoordGrid.INVALID) {
            "Avatar position must be updated via playerinfo#updateCoord before sending RebuildLogin/ReconnectOk."
        }
        byteBuf.toBitBuf().use { buffer ->
            buffer.pBits(30, avatar.currentCoord.packed)
            setHighResolution(localIndex)
            highResolutionIndices[highResolutionCount++] = localIndex.toShort()
            for (i in 1 until PROTOCOL_CAPACITY) {
                if (i == localIndex) {
                    continue
                }
                val lowResolutionPosition = protocol.getLowResolutionPosition(i)
                buffer.pBits(18, lowResolutionPosition.packed)
                lowResolutionIndices[lowResolutionCount++] = i.toShort()
            }
        }
        // Sync the coordinate delta here!
        // Meaning if a player info is sent afterwards, it will not re-send the delta
        // which often results in the coordinate being 2x'd at the client
        avatar.postUpdate()
    }

    /**
     * Resets any existing state.
     * Cached state should be re-assigned from the server as a result of this.
     */
    public fun onReconnect() {
        if (isDestroyed()) return
        this.buffer = null
        highResMovementBuffer = null

        lowResolutionIndices.fill(0)
        lowResolutionCount = 0
        highResolutionIndices.fill(0)
        highResolutionCount = 0
        highResolutionPlayers.fill(0L)
        highResolutionExtendedInfoTrackedPlayers.fill(0L)
        extendedInfoCount = 0
        extendedInfoIndices.fill(0)
        stationary.fill(0)
        observerExtendedInfoFlags.reset()
        avatar.postUpdate()
        avatar.extendedInfo.onReconnect()
    }

    /**
     * Ensures that the state has been correctly reset and a reconnect packet can continue.
     * @throws IllegalStateException if the state has not fully been cleaned up.
     */
    internal fun ensureReconnectCalled() {
        if (!isCleanState()) {
            throw IllegalStateException(
                "In order to use LoginResponse.ReconnectOk packet, " +
                    "playerinfo#onReconnect, npcInfo#onReconnect " +
                    "and worldEntityInfo#onReconnect must be called!",
            )
        }
    }

    /**
     * Checks whether all the info has been reset for this packet, ensuring that
     * a reconnect packet can successfully be initialized.
     * @return whether all the state has been reset.
     */
    private fun isCleanState(): Boolean =
        buffer == null &&
            highResMovementBuffer == null &&
            lowResolutionCount == 0 &&
            highResolutionCount == 0 &&
            extendedInfoCount == 0

    /**
     * Precalculates all the bitcodes for this player, for high-resolution updates.
     * This function will be thread-safe relative to other players and can be calculated concurrently for all players.
     */
    internal fun prepareBitcodes() {
        this.avatar.extendedInfo.observedChatStorage
            .reset()
        this.highResMovementBuffer = prepareHighResMovement()
    }

    /**
     * Pre-computes extended info blocks for this player. Only extended info blocks
     * which were flagged during this cycle will be pre-computed, with any on-demand
     * extended info blocks excluded in pre-computations altogether.
     */
    internal fun precomputeExtendedInfo() {
        avatar.extendedInfo.precompute()
    }

    /**
     * Writes the extended info blocks of everyone who were marked
     * during [pBitcodes] to the [buffer]. This will utilize fast native memory copying for any
     * pre-computed extended info blocks. For any observer-dependent info blocks,
     * a new [ByteBuf] instance is allocated from the [allocator], which is then written
     * the information, followed by a fast native copy, which is further followed by releasing
     * this temporary buffer back. As mentioned before, it is highly suggested to use a pooled
     * implementation of the [allocator].
     * This function is thread-safe relative to other players and can be computed for all players
     * concurrently.
     */
    internal fun putExtendedInfo() {
        val jagBuffer = backingBuffer().toJagByteBuf()
        for (i in 0 until extendedInfoCount) {
            val index = extendedInfoIndices[i].toInt()
            val other = protocol.getPlayerInfo(index)
            // If other is null at this point, it means it was destroyed mid-processing at an earlier
            // stage. In order to avoid the issue escalating further by throwing errors for every player
            // that was in vicinity of the player that got destroyed, we simply write no-mask-update,
            // even though a mask update was requested at an earlier stage.
            // The next game tick, the player will be removed as the info is null, which is one of
            // the conditions for removing another player from tracking.
            if (other == null) {
                jagBuffer.p1(0)
                continue
            }
            val observerFlag = observerExtendedInfoFlags.getFlag(index)
            val tracked =
                other.avatar.extendedInfo.pExtendedInfo(
                    oldSchoolClientType,
                    jagBuffer,
                    observerFlag,
                    avatar.extendedInfo,
                    extendedInfoCount - i,
                )
            if (tracked) {
                setHighResolutionExtendedInfoTracked(index)
            }
        }
    }

    /**
     * Writes to the actual buffers the prepared bitcodes and extended information.
     * This function will be thread-safe relative to other players and can be calculated concurrently for all players.
     */
    internal fun pBitcodes() {
        avatar.resize(highResolutionCount)
        val buffer = allocBuffer()
        val bitBuf = buffer.toBitBuf()
        bitBuf.use { processHighResolution(it, skipStationary = true) }
        bitBuf.use { processHighResolution(it, skipStationary = false) }
        bitBuf.use { processLowResolution(it, skipStationary = false) }
        bitBuf.use { processLowResolution(it, skipStationary = true) }
    }

    /**
     * Processes low resolution updates for all the players who are currently
     * in our low resolution view.
     * @param buffer the buffer into which to write the bitcodes regarding each player.
     * @param skipStationary whether to skip any players who were marked as stationary last cycle.
     */
    private fun processLowResolution(
        buffer: BitBuf,
        skipStationary: Boolean,
    ) {
        var skips = -1
        for (i in 0 until lowResolutionCount) {
            val index = lowResolutionIndices[i].toInt()
            val wasStationary = stationary[index].toInt() and WAS_STATIONARY != 0
            if (skipStationary == wasStationary) {
                continue
            }
            val other = protocol.getPlayerInfo(index)
            val lowResolutionBuffer = globalLowResolutionPositionRepository.getBuffer(index)
            if (other == null) {
                if (lowResolutionBuffer != null) {
                    if (skips > -1) {
                        pStationary(buffer, skips)
                        skips = -1
                    }
                    buffer.pBits(1, 1)
                    buffer.pBits(lowResolutionBuffer)
                    continue
                }
                skips++
                stationary[index] = (stationary[index].toInt() or IS_STATIONARY).toByte()
                continue
            }
            val visible = shouldMoveToHighResolution(other)
            if (!visible && lowResolutionBuffer == null) {
                skips++
                stationary[index] = (stationary[index].toInt() or IS_STATIONARY).toByte()
                continue
            }
            if (skips > -1) {
                pStationary(buffer, skips)
                skips = -1
            }
            if (!visible) {
                buffer.pBits(1, 1)
                buffer.pBits(lowResolutionBuffer!!)
                continue
            }
            pLowResToHighRes(buffer, other)
        }
        if (skips > -1) {
            pStationary(buffer, skips)
        }
    }

    /**
     * Writes a transition from low resolution to high resolution for the given player.
     * @param buffer the buffer into which to write the transition.
     * @param other the player who is being moved from low resolution to high resolution.
     */
    private fun pLowResToHighRes(
        buffer: BitBuf,
        other: PlayerInfo,
    ) {
        val index = other.localIndex
        // The above one-liner pBits is equal to this comment:
        // buffer.pBits(1, 1)
        // buffer.pBits(2, 0)
        buffer.pBits(3, 1 shl 2)
        val lowResBuf = globalLowResolutionPositionRepository.getBuffer(index)
        if (lowResBuf != null) {
            buffer.pBits(1, 1)
            buffer.pBits(lowResBuf)
        } else {
            buffer.pBits(1, 0)
        }
        val (_, x, z) = other.avatar.currentCoord

        buffer.pBits(13, x)
        buffer.pBits(13, z)

        // Get a flags of all the extended info blocks that are 'outdated' to us and must be sent again.
        val extraFlags =
            other.avatar.extendedInfo.getLowToHighResChangeExtendedInfoFlags(
                avatar.extendedInfo,
                oldSchoolClientType,
            )
        // Mark those flags as observer-dependent.
        observerExtendedInfoFlags.addFlag(index, extraFlags)
        stationary[index] = (stationary[index].toInt() or IS_STATIONARY).toByte()
        setHighResolution(index)
        val flag = other.avatar.extendedInfo.flags or observerExtendedInfoFlags.getFlag(index)
        val hasExtendedInfoBlock = flag != 0
        if (hasExtendedInfoBlock) {
            extendedInfoIndices[extendedInfoCount++] = index.toShort()
            buffer.pBits(1, 1)
        } else {
            setHighResolutionExtendedInfoTracked(index)
            buffer.pBits(1, 0)
        }
    }

    /**
     * Processes high resolution updates for all the players who are currently
     * in our high resolution view.
     * @param buffer the buffer into which to write the bitcodes regarding each player.
     * @param skipStationary whether to skip any players who were marked as stationary last cycle.
     */
    private fun processHighResolution(
        buffer: BitBuf,
        skipStationary: Boolean,
    ) {
        var skips = -1
        for (i in 0 until highResolutionCount) {
            val index = highResolutionIndices[i].toInt()
            val wasStationary = (stationary[index].toInt() and WAS_STATIONARY) != 0
            if (skipStationary == wasStationary) {
                continue
            }
            val other = protocol.getPlayerInfo(index)
            if (!shouldStayInHighResolution(other)) {
                if (skips > -1) {
                    pStationary(buffer, skips)
                    skips = -1
                }
                pHighToLowResChange(buffer, index)
                continue
            }

            // If we still haven't tracked extended info for them, re-try
            if (!isHighResolutionExtendedInfoTracked(index)) {
                val extraFlags =
                    other.avatar.extendedInfo.getLowToHighResChangeExtendedInfoFlags(
                        avatar.extendedInfo,
                        oldSchoolClientType,
                    )
                observerExtendedInfoFlags.addFlag(index, extraFlags)
            }
            val flag = other.avatar.extendedInfo.flags or observerExtendedInfoFlags.getFlag(index)
            val hasExtendedInfoBlock = flag != 0
            if (!hasExtendedInfoBlock) {
                setHighResolutionExtendedInfoTracked(index)
            }
            val highResBuf = other.highResMovementBuffer
            val skipped = !hasExtendedInfoBlock && highResBuf == null
            if (!skipped) {
                if (skips > -1) {
                    pStationary(buffer, skips)
                    skips = -1
                }
                pHighRes(buffer, index, hasExtendedInfoBlock, highResBuf)
                continue
            }
            skips++
            stationary[index] = (stationary[index].toInt() or IS_STATIONARY).toByte()
        }
        if (skips > -1) {
            pStationary(buffer, skips)
        }
    }

    /**
     * Writes the [count] of consecutive stationary players
     * using [run-length encoding](https://en.wikipedia.org/wiki/Run-length_encoding).
     * @param buffer the buffer into which to write the encoded count.
     * @param count the count of players that were skipped.
     * The actual number that is written will always be 1 less, as the client automatically
     * includes 1 in the total value through the presence of a stationary block in the first place.
     */
    private fun pStationary(
        buffer: BitBuf,
        count: Int,
    ) {
        // The below code is a branchless variant of this:
        // buffer.pBits(1, 0)
        // when {
        //     count == 0 -> buffer.pBits(2, 0)
        //     count <= 0x1F -> {
        //         buffer.pBits(2, 1)
        //         buffer.pBits(5, count)
        //     }
        //     count <= 0xFF -> {
        //         buffer.pBits(2, 2)
        //         buffer.pBits(8, count)
        //     }
        //     else -> {
        //         buffer.pBits(2, 3)
        //         buffer.pBits(11, count)
        //     }
        // }
        //
        // The branching causes a significant (~15-20%) performance loss in the extreme
        // end-case benchmarks, so it's best to eliminate it.

        // (Special thanks to Greg for figuring out the magic below!)
        // Positive signum the bits proceeding the 1st, 5th and 8th bit to give a value 1 - 3 to
        // represent > 0, > 31 and > 255 respectively.
        val lowerBits = (-count ushr 31)
        val higherBits = (-(count shr 5) ushr 31) + (-(count shr 8) ushr 31)
        val bitCountOpcode = lowerBits + higherBits
        val valueBitCount = (lowerBits * 5) + (higherBits * 3)
        buffer.pBits(3 + valueBitCount, count or (bitCountOpcode shl valueBitCount))
    }

    /**
     * Writes high resolution information about a player into the [buffer].
     * @param buffer the buffer into which to write the bitcodes.
     * @param index the index of the player whose information we are writing.
     * @param extendedInfo whether this player also had extended info block changes.
     * @param highResBuf the pre-computed bit buffer regarding this player's movement.
     */
    private fun pHighRes(
        buffer: BitBuf,
        index: Int,
        extendedInfo: Boolean,
        highResBuf: UnsafeLongBackedBitBuf?,
    ) {
        buffer.pBits(1, 1)
        if (extendedInfo) {
            extendedInfoIndices[extendedInfoCount++] = index.toShort()
            buffer.pBits(1, 1)
        } else {
            buffer.pBits(1, 0)
        }
        if (highResBuf != null) {
            buffer.pBits(highResBuf)
        } else {
            buffer.pBits(2, 0)
        }
    }

    /**
     * Writes a high resolution to low resolution change for the player.
     * @param buffer the buffer into which to write the bitcodes.
     * @param index the index of the player that is being moved to low resolution.
     */
    private fun pHighToLowResChange(
        buffer: BitBuf,
        index: Int,
    ) {
        unsetHighResolution(index)
        unsetHighResolutionExtendedInfoTracked(index)
        // The one-liner pBits is equal to the below comment:
        // buffer.pBits(1, 1)
        // buffer.pBits(1, 0)
        // buffer.pBits(2, 0)
        buffer.pBits(4, 1 shl 3)
        val buf = globalLowResolutionPositionRepository.getBuffer(index)
        if (buf != null) {
            buffer.pBits(1, 1)
            buffer.pBits(buf)
        } else {
            buffer.pBits(1, 0)
        }
    }

    /**
     * Checks if [other] is visible to us considering our [PlayerAvatar.resizeRange].
     * This function utilizes experimental contracts to avoid an unnecessary null-check,
     * as if the function returns true, the parameter cannot ever be null.
     * @param other the player whom to check.
     * @return true if the other should be moved to low resolution.
     */
    @OptIn(ExperimentalContracts::class)
    private fun shouldStayInHighResolution(other: PlayerInfo?): Boolean {
        contract {
            returns(true) implies (other != null)
        }
        // If the avatar is no longer logged in, remove it
        if (other == null) {
            return false
        }
        // Do not add or remove local player
        if (other.localIndex == localIndex) {
            return true
        }
        if (other.avatar.hidden) {
            return false
        }
        // If the avatar was allocated on this cycle, ensure we remove (and potentially re-add later)
        // this avatar. This is due to someone logging out and another player taking the avatar the same
        // cycle - which would otherwise potentially go by unnoticed, with the client assuming nothing changed.
        if (other.avatar.allocateCycle == PlayerInfoProtocol.cycleCount) {
            return false
        }
        val coord = other.avatar.currentCoord
        if (!coord.inDistance(this.avatar.currentCoord, this.avatar.resizeRange)) {
            return false
        }
        val buildArea = this.buildArea
        return buildArea == BuildArea.INVALID || coord in buildArea
    }

    /**
     * Checks if [other] is visible to us considering our [PlayerAvatar.resizeRange].
     * This function utilizes experimental contracts to avoid an unnecessary null-check,
     * as if the function returns true, the parameter cannot ever be null.
     * @param other the player whom to check.
     * @return true if the other player should be moved to high resolution.
     */
    @OptIn(ExperimentalContracts::class)
    private fun shouldMoveToHighResolution(other: PlayerInfo?): Boolean {
        contract {
            returns(true) implies (other != null)
        }
        // If the avatar is no longer logged in, remove it
        if (other == null || other.localIndex == localIndex) {
            return false
        }
        if (other.avatar.hidden) {
            return false
        }
        val coord = other.avatar.currentCoord
        if (!coord.inDistance(this.avatar.currentCoord, this.avatar.resizeRange)) {
            return false
        }
        val buildArea = this.buildArea
        return buildArea == BuildArea.INVALID || coord in buildArea
    }

    /**
     * Allocates a new buffer from the [allocator] with a capacity of [BUF_CAPACITY].
     * The old [buffer] will not be released, as that is the duty of the encoder class.
     */
    private fun allocBuffer(): ByteBuf {
        // Acquire a new buffer with each cycle, in case the previous one isn't fully written out yet
        val buffer = allocator.buffer(BUF_CAPACITY, BUF_CAPACITY)
        this.buffer = buffer
        recycler += buffer
        return buffer
    }

    /**
     * Reset any temporary properties from this cycle.
     */
    internal fun postUpdate() {
        this.avatar.postUpdate()
        avatar.extendedInfo.postUpdate()
        lowResolutionCount = 0
        highResolutionCount = 0
        // Only need to reset the count here, the actual numbers don't matter.
        extendedInfoCount = 0
        for (i in 1 until PROTOCOL_CAPACITY) {
            stationary[i] = (stationary[i].toInt() shr 1).toByte()
            if (isHighResolution(i)) {
                highResolutionIndices[highResolutionCount++] = i.toShort()
            } else {
                lowResolutionIndices[lowResolutionCount++] = i.toShort()
            }
        }
        observerExtendedInfoFlags.reset()
        avatar.extendedInfo.postUpdate()
    }

    /**
     * Resets all the primitive properties of this class which can be lazy-reset.
     * We utilize lazy resetting here as there's no guarantee that a given [PlayerInfo]
     * object will ever be re-used. Due to the nature of soft references, it is possible
     * for the garbage collector to collect it when it truly needs it. In order to reduce processing
     * time, we skip resetting these properties on de-allocation.
     * @param index the index of the new player who will be utilizing this player info object.
     * @param oldSchoolClientType the client the new player is utilizing.
     */
    override fun onAlloc(
        index: Int,
        oldSchoolClientType: OldSchoolClientType,
    ) {
        this.localIndex = index
        avatar.extendedInfo.localIndex = index
        this.oldSchoolClientType = oldSchoolClientType
        this.buildArea = BuildArea.INVALID
        avatar.reset()
        this.avatar.allocateCycle = PlayerInfoProtocol.cycleCount
        lowResolutionIndices.fill(0)
        lowResolutionCount = 0
        highResolutionIndices.fill(0)
        highResolutionCount = 0
        highResolutionPlayers.fill(0L)
        highResolutionExtendedInfoTrackedPlayers.fill(0L)
        extendedInfoCount = 0
        extendedInfoIndices.fill(0)
        stationary.fill(0)
        observerExtendedInfoFlags.reset()
        this.buffer = null
    }

    /**
     * Clears any references to temporary buffers on de-allocation, as we don't want these
     * to stick around for extended periods of time. Any primitive properties will remain untouched.
     */
    override fun onDealloc() {
        this.buffer = null
        avatar.extendedInfo.reset()
        highResMovementBuffer = null
    }

    /**
     * Prepares the high resolution movement block by checking the player's absolute coordinate
     * differences.
     * @return unsafe long-backed bit buffer that encodes the information into a 'long' primitive,
     * rather than a real byte buffer, in order to reduce unnecessary computations.
     */
    private fun prepareHighResMovement(): UnsafeLongBackedBitBuf? {
        val oldCoord = avatar.lastCoord
        val newCoord = avatar.currentCoord
        if (oldCoord == newCoord) {
            return null
        }
        val buffer = UnsafeLongBackedBitBuf()
        val deltaX = newCoord.x - oldCoord.x
        val deltaZ = newCoord.z - oldCoord.z
        val deltaLevel = newCoord.level - oldCoord.level
        val absX = abs(deltaX)
        val absZ = abs(deltaZ)
        if (deltaLevel != 0 || absX > 2 || absZ > 2) {
            if (absX >= 16 || absZ >= 16) {
                pLargeTeleport(buffer, deltaX, deltaZ, deltaLevel)
            } else {
                pSmallTeleport(buffer, deltaX, deltaZ, deltaLevel)
            }
        } else if (absX == 2 || absZ == 2) {
            pRun(buffer, deltaX, deltaZ)
        } else {
            // Guaranteed to be walking here, as our 'oldCoord == newCoord' covers the stationary condition.
            pWalk(buffer, deltaX, deltaZ)
        }
        return buffer
    }

    /**
     * Writes a single cell movement bitcode.
     * @param buffer the buffer into which to write the bitcode.
     * @param deltaX the x-coordinate delta the player moved.
     * @param deltaZ the z-coordinate delta the player moved.
     * @throws ArrayIndexOutOfBoundsException if the provided deltas do not result in a
     * one-cell movement.
     */
    @Throws(ArrayIndexOutOfBoundsException::class)
    private fun pWalk(
        buffer: UnsafeLongBackedBitBuf,
        deltaX: Int,
        deltaZ: Int,
    ) {
        buffer.pBits(2, 1)
        buffer.pBits(3, CellOpcodes.singleCellMovementOpcode(deltaX, deltaZ))
    }

    /**
     * Writes a dual cell movement bitcode.
     * @param buffer the buffer into which to write the bitcode.
     * @param deltaX the x-coordinate delta the player moved.
     * @param deltaZ the z-coordinate delta the player moved.
     * @throws ArrayIndexOutOfBoundsException if the provided deltas do not result in a
     * dual-cell movement.
     */
    @Throws(ArrayIndexOutOfBoundsException::class)
    private fun pRun(
        buffer: UnsafeLongBackedBitBuf,
        deltaX: Int,
        deltaZ: Int,
    ) {
        buffer.pBits(2, 2)
        buffer.pBits(4, CellOpcodes.dualCellMovementOpcode(deltaX, deltaZ))
    }

    /**
     * Writes a low-distance movement block, capped to a maximum delta of 15 coordinates
     * as well as any level changes.
     * @param buffer the buffer into which to write the bitcode.
     * @param deltaX the x-coordinate delta the player moved.
     * @param deltaZ the z-coordinate delta the player moved.
     * @param deltaLevel the level-coordinate delta the player moved.
     */
    private fun pSmallTeleport(
        buffer: UnsafeLongBackedBitBuf,
        deltaX: Int,
        deltaZ: Int,
        deltaLevel: Int,
    ) {
        buffer.pBits(2, 3)
        buffer.pBits(1, 0)
        buffer.pBits(2, deltaLevel and 0x3)
        buffer.pBits(5, deltaX and 0x1F)
        buffer.pBits(5, deltaZ and 0x1F)
    }

    /**
     * Writes a long-distance movement block, completely uncapped for the game world.
     * @param buffer the buffer into which to write the bitcode.
     * @param deltaX the x-coordinate delta the player moved.
     * @param deltaZ the z-coordinate delta the player moved.
     * @param deltaLevel the level-coordinate delta the player moved.
     */
    private fun pLargeTeleport(
        buffer: UnsafeLongBackedBitBuf,
        deltaX: Int,
        deltaZ: Int,
        deltaLevel: Int,
    ) {
        buffer.pBits(2, 3)
        buffer.pBits(1, 1)
        buffer.pBits(2, deltaLevel and 0x3)
        buffer.pBits(14, deltaX and 0x3FFF)
        buffer.pBits(14, deltaZ and 0x3FFF)
    }

    public companion object {
        /**
         * The default capacity of the backing byte buffer into which all player info is written.
         */
        private const val BUF_CAPACITY: Int = 40_000

        /**
         * The flag indicating that a player was stationary in the previous cycle.
         */
        private const val WAS_STATIONARY: Int = 0x1

        /**
         * The flag indicating that a player is stationary in the current cycle.
         */
        private const val IS_STATIONARY: Int = 0x2
    }
}
