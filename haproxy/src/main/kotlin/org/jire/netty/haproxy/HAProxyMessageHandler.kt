package org.jire.netty.haproxy

import com.github.michaelbull.logging.InlineLogger
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.haproxy.HAProxyCommand
import io.netty.handler.codec.haproxy.HAProxyMessage
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder
import io.netty.handler.timeout.IdleStateEvent
import org.jire.netty.haproxy.HAProxyAttributes.haproxyAttribute
import org.jire.netty.haproxy.HAProxyHandlerNames.HAPROXY_CHANNEL_INITIALIZER_CHILD_NAME
import org.jire.netty.haproxy.HAProxyHandlerNames.HAPROXY_IDLE_STATE_HANDLER_NAME

/**
 * Handles the [HAProxyMessage] received from the [HAProxy](https://en.wikipedia.org/wiki/HAProxy) protocol to set
 * the the [HAProxyAttributes.KEY] attribute for the channel.
 *
 * This handler should be added after a [HAProxyMessageDecoder] in the pipeline.
 *
 * This handler is [Sharable] and thus can be added to multiple pipelines.
 */
@Sharable
public class HAProxyMessageHandler(
    override val childHandler: ChannelInboundHandler,
) : SimpleChannelInboundHandler<HAProxyMessage>(true),
    HAProxyParentHandler {
    override fun channelActive(ctx: ChannelHandlerContext) {
        // Because auto-read may be disabled, we need to trigger the handling
        ctx.read()

        ctx.fireChannelActive()
    }

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        ctx.fireUserEventTriggered(evt)

        if (evt is IdleStateEvent) {
            logger.debug {
                "Channel has gone idle during HAProxy message handling, closing for ${ctx.channel()}"
            }
            ctx.close()
        }
    }

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        msg: HAProxyMessage,
    ) {
        val command = msg.command()
        logger.trace { "Received HAProxy command $command from ${ctx.channel()}" }
        when (command) {
            HAProxyCommand.PROXY -> handleProxyCommand(ctx, msg)
            HAProxyCommand.LOCAL -> handleLocalCommand(ctx)

            else -> {
                logger.warn {
                    "Received unsupported HAProxy command $command from ${ctx.channel()}"
                }
                ctx.close()
            }
        }
    }

    private fun handleProxyCommand(
        ctx: ChannelHandlerContext,
        msg: HAProxyMessage,
    ) {
        val attribute =
            HAProxyAttribute(
                msg.protocolVersion(),
                msg.proxiedProtocol(),
                msg.sourceAddress(),
                msg.sourcePort(),
                msg.destinationAddress(),
                msg.destinationPort(),
            )

        val channel = ctx.channel()
        channel.haproxyAttribute = attribute

        logger.debug { "Set HAProxy attribute $attribute for channel $channel" }

        val pipeline = ctx.pipeline()
        pipeline.remove(HAPROXY_IDLE_STATE_HANDLER_NAME)
        pipeline.replace(
            this@HAProxyMessageHandler,
            HAPROXY_CHANNEL_INITIALIZER_CHILD_NAME,
            childHandler,
        )

        channel.eventLoop().execute {
            // Because auto-read may be disabled, we need to trigger the next read
            ctx.read()
        }
    }

    private fun handleLocalCommand(ctx: ChannelHandlerContext) {
        ctx.close()
    }

    private companion object {
        private val logger = InlineLogger()
    }
}
