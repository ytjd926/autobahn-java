///////////////////////////////////////////////////////////////////////////////
//
//   AutobahnJava - http://crossbar.io/autobahn
//
//   Copyright (c) Crossbar.io Technologies GmbH and contributors
//
//   Licensed under the MIT License.
//   http://www.opensource.org/licenses/mit-license.php
//
///////////////////////////////////////////////////////////////////////////////

package io.crossbar.autobahn.wamp.transports;

import java.util.logging.Logger;

import io.crossbar.autobahn.wamp.interfaces.ISerializer;
import io.crossbar.autobahn.wamp.interfaces.ITransport;
import io.crossbar.autobahn.wamp.interfaces.ITransportHandler;
import io.crossbar.autobahn.wamp.serializers.CBORSerializer;
import io.crossbar.autobahn.wamp.serializers.JSONSerializer;
import io.crossbar.autobahn.wamp.serializers.MessagePackSerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;


public class NettyWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOGGER = Logger.getLogger(
            NettyWebSocketClientHandler.class.getName());

    private final WebSocketClientHandshaker mHandshaker;
    private final ITransport mTransport;
    private ChannelPromise mHandshakeFuture;
    private ITransportHandler mTransportHandler;
    private boolean mWasCleanClose;

    public NettyWebSocketClientHandler(WebSocketClientHandshaker handshaker, ITransport transport,
                                       ITransportHandler transportHandler) {
        mHandshaker = handshaker;
        mTransport = transport;
        mTransportHandler = transportHandler;
    }

    ChannelFuture getHandshakeFuture() {
        return mHandshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        mHandshakeFuture = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        mHandshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOGGER.info("WebSocket Client disconnected!");
        mTransportHandler.onDisconnect(mWasCleanClose);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!mHandshaker.isHandshakeComplete()) {
            FullHttpResponse response = (FullHttpResponse) msg;
            String negotiatedSerializer = response.headers().get("Sec-WebSocket-Protocol");
            LOGGER.info(String.format("Negotiated serializer=%s", negotiatedSerializer));
            ISerializer serializer = initializeSerializer(negotiatedSerializer);
            mHandshaker.finishHandshake(ch, response);
            mHandshakeFuture.setSuccess();
            mTransportHandler.onConnect(mTransport, serializer);

        } else if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.status() +
                            ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');

        } else if (msg instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binaryWebSocketFrame = (BinaryWebSocketFrame) msg;
            byte[] payload = new byte[binaryWebSocketFrame.content().readableBytes()];
            LOGGER.info(String.format("Received binary frame, content length=%s", payload.length));
            binaryWebSocketFrame.content().readBytes(payload);
            mTransportHandler.onMessage(payload, true);

        } else if (msg instanceof TextWebSocketFrame) {
            TextWebSocketFrame textWebSocketFrame = (TextWebSocketFrame) msg;
            byte[] payload = new byte[textWebSocketFrame.content().readableBytes()];
            LOGGER.info(String.format("Received Text frame, content length=%s", payload.length));
            textWebSocketFrame.content().readBytes(payload);
            mTransportHandler.onMessage(payload, false);

        } else if (msg instanceof PingWebSocketFrame) {
            PingWebSocketFrame pingWebSocketFrame = (PingWebSocketFrame) msg;
            ctx.writeAndFlush(new PongWebSocketFrame(pingWebSocketFrame.content().retain()));

        } else if (msg instanceof PongWebSocketFrame) {
            // Not really doing anything here.
            LOGGER.info("WebSocket Client received pong.");

        } else if (msg instanceof CloseWebSocketFrame) {
            CloseWebSocketFrame closeWebSocketFrame = (CloseWebSocketFrame) msg;
            LOGGER.info(String.format(
                    "Received Close frame, code=%s, reason=%s",
                    closeWebSocketFrame.statusCode(), closeWebSocketFrame.reasonText()));
            close(ctx, closeWebSocketFrame.statusCode() == 1000);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                close(ctx, false);
            } else if (event.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (!mHandshakeFuture.isDone()) {
            mHandshakeFuture.setFailure(cause);
        }
        close(ctx, false);
    }

    private void close(ChannelHandlerContext context, boolean wasClean) {
        context.close();
        mWasCleanClose = wasClean;
    }

    void close(Channel channel, boolean wasClean) {
        channel.close();
        mWasCleanClose = wasClean;
    }

    private ISerializer initializeSerializer(String negotiatedSerializer) throws Exception {
        switch (negotiatedSerializer) {
            case CBORSerializer.NAME:
                return new CBORSerializer();
            case JSONSerializer.NAME:
                return new JSONSerializer();
            case MessagePackSerializer.NAME:
                return new MessagePackSerializer();
            default:
                throw new IllegalArgumentException("Unsupported serializer.");
        }
    }
}
