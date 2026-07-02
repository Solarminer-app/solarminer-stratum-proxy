package de.verdox.solarminer.solarminerstratumproxy.v1.connection;

import de.verdox.solarminer.solarminerstratumproxy.v1.MinerSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinerInboundHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(MinerInboundHandler.class);

    private final MinerSessionManager sessionManager;
    private final String coinName;

    private MinerSession currentSession;

    public MinerInboundHandler(MinerSessionManager sessionManager, String coinName) {
        this.sessionManager = sessionManager;
        this.coinName = coinName;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.currentSession = sessionManager.createSession(ctx.channel(), coinName);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        currentSession.handleMessageFromMiner(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (currentSession != null) {
            currentSession.disconnect();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Network error: {}", cause.getMessage());
        ctx.close();
    }
}
