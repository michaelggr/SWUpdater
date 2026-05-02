package com.swupdater.capture

import android.util.Log
import com.swupdater.util.AppLog
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

class CaptureProxyServer(
    private val port: Int = 8080,
    private val certManager: CertificateManager,
    private val parser: GameDataParser
) {

    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

    @Volatile
    var isRunning = false
        private set

    var onGameTrafficDetected: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ProxyServer"

        val GAME_DOMAINS = setOf(
            "summonerswar.sky3ds.com",
            "com2us.com",
            "withhive.com",
            "hive.com2us.com",
            "api.withhive.com",
            "qpyou.cn"
        )

        fun isGameDomain(hostname: String): Boolean {
            return GAME_DOMAINS.any { hostname.equals(it, ignoreCase = true) || hostname.endsWith(".$it") }
        }
    }

    fun start(): Boolean {
        if (isRunning) return true

        try {
            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup()

            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(HttpServerCodec())
                        ch.pipeline().addLast(HttpObjectAggregator(65536))
                        ch.pipeline().addLast(ProxyHandler())
                    }
                })

            val future = bootstrap.bind("127.0.0.1", port).sync()
            serverChannel = future.channel()
            isRunning = true
            AppLog.i(TAG, "代理服务器启动成功，端口: $port")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "代理服务器启动失败", e)
            AppLog.e(TAG, "代理服务器启动失败: ${e.message}")
            stop()
            return false
        }
    }

    fun stop() {
        isRunning = false
        serverChannel?.close()?.syncUninterruptibly()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
        serverChannel = null
        workerGroup = null
        bossGroup = null
        AppLog.i(TAG, "代理服务器已停止")
    }

    private inner class ProxyHandler : SimpleChannelInboundHandler<HttpRequest>() {

        override fun channelRead0(ctx: ChannelHandlerContext, request: HttpRequest) {
            if ("CONNECT".equals(request.method().name(), ignoreCase = true)) {
                handleConnect(ctx, request)
            } else {
                handleHttp(ctx, request)
            }
        }

        private fun handleConnect(ctx: ChannelHandlerContext, request: HttpRequest) {
            val hostPort = request.uri().split(":")
            val hostname = hostPort[0]
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

            if (isGameDomain(hostname)) {
                handleMitmConnect(ctx, hostname, port)
            } else {
                handleDirectConnect(ctx, hostname, port)
            }
        }

        private fun handleMitmConnect(ctx: ChannelHandlerContext, hostname: String, port: Int) {
            AppLog.d(TAG, "MITM 拦截: $hostname:$port")
            onGameTrafficDetected?.invoke(hostname)

            val leafResult = certManager.getLeafCertificate(hostname)
            if (leafResult == null) {
                AppLog.e(TAG, "无法生成叶子证书: $hostname")
                ctx.close()
                return
            }

            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            ctx.writeAndFlush(response)

            // 代理作为 SSL 服务端，与客户端建立 TLS 连接
            val serverSslContext = buildServerSslContext(leafResult)
            if (serverSslContext == null) {
                AppLog.e(TAG, "SSL 上下文创建失败: $hostname")
                ctx.close()
                return
            }

            val pipeline = ctx.pipeline()
            pipeline.remove(HttpServerCodec::class.java)
            pipeline.remove(HttpObjectAggregator::class.java)
            pipeline.remove(this::class.java)

            // 客户端侧：代理作为 TLS 服务端解密客户端流量
            pipeline.addLast(serverSslContext.newHandler(ctx.alloc()))

            connectToRemote(hostname, port) { remoteChannel ->
                // 远程侧：代理作为 TLS 客户端加密发往真实服务器的流量
                val clientSslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build()
                remoteChannel.pipeline().addLast(clientSslContext.newHandler(remoteChannel.alloc(), hostname, port))

                // 客户端→代理→远程：解密后的请求明文转发（加密后发出）
                pipeline.addLast(MitmRelayHandler(ctx.channel(), hostname, parser, isRequest = true))
                // 远程→代理→客户端：解密后的响应明文转发（加密后发出）
                remoteChannel.pipeline().addLast(MitmRelayHandler(remoteChannel, hostname, parser, isRequest = false))
                ctx.channel().config().isAutoRead = true
            }
        }

        private fun handleDirectConnect(ctx: ChannelHandlerContext, hostname: String, port: Int) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            ctx.writeAndFlush(response)

            val pipeline = ctx.pipeline()
            pipeline.remove(HttpServerCodec::class.java)
            pipeline.remove(HttpObjectAggregator::class.java)
            pipeline.remove(this::class.java)

            pipeline.addLast(RelayHandler(ctx.channel()))

            connectToRemote(hostname, port) { remoteChannel ->
                remoteChannel.pipeline().addLast(RelayHandler(remoteChannel))
                ctx.channel().config().isAutoRead = true
            }
        }

        private fun handleHttp(ctx: ChannelHandlerContext, request: HttpRequest) {
            val host = request.headers().get("Host") ?: ""
            val hostname = host.split(":")[0]
            val port = host.split(":").getOrNull(1)?.toIntOrNull() ?: 80

            connectToRemote(hostname, port) { remoteChannel ->
                val pipeline = remoteChannel.pipeline()
                pipeline.addLast(HttpClientCodec())
                pipeline.addLast(RelayHandler(ctx.channel()))

                ctx.pipeline().remove(this::class.java)
                ctx.pipeline().addLast(RelayHandler(remoteChannel))

                remoteChannel.writeAndFlush(request.retain())
            }
        }

        private fun connectToRemote(hostname: String, port: Int, onConnected: (Channel) -> Unit) {
            val bootstrap = Bootstrap()
            bootstrap.group(workerGroup!!)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.AUTO_READ, false)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {}
                })

            val future = bootstrap.connect(hostname, port)
            future.addListener { f ->
                if (f.isSuccess) {
                    val remoteChannel = future.channel()
                    onConnected(remoteChannel)
                } else {
                    AppLog.e(TAG, "连接远程服务器失败: $hostname:$port")
                    future.channel().close()
                }
            }
        }

        private fun buildServerSslContext(leafResult: CertificateManager.LeafCertResult): SslContext? {
            return try {
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                val certChain = arrayOf(leafResult.certificate, certManager.caCertificate!!)
                keyStore.setKeyEntry("proxy", leafResult.keyPair.private, charArrayOf(), certChain)

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, charArrayOf())

                SslContextBuilder.forServer(kmf)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "构建 SSL 上下文失败", e)
                null
            }
        }
    }

    private class RelayHandler(private val relayChannel: Channel) : SimpleChannelInboundHandler<ByteBuf>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            if (relayChannel.isActive) {
                relayChannel.writeAndFlush(msg.retain())
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            relayChannel.close()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            relayChannel.close()
        }
    }

    private class MitmRelayHandler(
        private val relayChannel: Channel,
        private val hostname: String,
        private val parser: GameDataParser,
        private val isRequest: Boolean
    ) : SimpleChannelInboundHandler<ByteBuf>() {

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.getBytes(msg.readerIndex(), bytes)

            // 只解析服务端响应（解密后的明文）
            if (!isRequest) {
                parser.processResponse(bytes, hostname)
            }

            if (relayChannel.isActive) {
                relayChannel.writeAndFlush(msg.retain())
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            relayChannel.close()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            relayChannel.close()
        }
    }
}
