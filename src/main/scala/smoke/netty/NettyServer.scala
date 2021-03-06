package smoke.netty

import com.typesafe.config.Config  
import akka.actor._  
import akka.dispatch.{ Future, Promise }  

import java.net.InetSocketAddress

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.util.CharsetUtil

import collection.JavaConversions._

import smoke._

class NettyServer(implicit val config: Config, system: ActorSystem) extends Server {
  val port = config.getInt("smoke.netty.port")

  val handler = new NettyServerHandler(log)
  val piplineFactory = new NettyServerPipelineFactory(handler)
  
  val bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory())  
  bootstrap.setPipelineFactory(piplineFactory)
  
  var channelOption: Option[Channel] = None
      
  def setApplication(application: (Request) => Future[Response]) {
    handler.setApplication(application)
  }
  
  def start() {
    channelOption = Some(bootstrap.bind(new InetSocketAddress(port)))
    println("Netty now accepting HTTP connections on port " + port.toString)
  }
  
  def stop() {
    channelOption map { channel => 
      channel.close.awaitUninterruptibly() 
      println("Netty no longer accepting HTTP connections")
    }
  }
}

class NettyServerPipelineFactory(handler: NettyServerHandler) 
  extends ChannelPipelineFactory {
  def getPipeline = {
    val p = Channels.pipeline

    p.addLast("decoder", new HttpRequestDecoder)
    p.addLast("aggregator", new HttpChunkAggregator(1048576))
    p.addLast("encoder", new HttpResponseEncoder)
    p.addLast("deflater", new HttpContentCompressor)
    p.addLast("handler", handler)
    p
  }
}

class NettyServerHandler(log: (Request, Response) => Unit)(implicit system: ActorSystem) extends SimpleChannelUpstreamHandler {
  import HttpHeaders.Names._
  import HttpHeaders.Values._
  
  implicit val dispatcher = system.dispatcher
    
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val address = e.getRemoteAddress
    val request = NettyRequest(address, e.getMessage.asInstanceOf[HttpRequest])
    
    application(request) map { response =>
      val status = HttpResponseStatus.valueOf(response.statusCode)
      val headers = response.headers
      val body = response.body
      val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)

      body match {
        case utf8: UTF8Data => nettyResponse.setContent(ChannelBuffers.copiedBuffer(utf8.data, CharsetUtil.UTF_8))
        case raw: RawData => nettyResponse.setContent(ChannelBuffers.copiedBuffer(raw.data))
      }

      if (request.keepAlive) {
        nettyResponse.setHeader(CONTENT_LENGTH, nettyResponse.getContent.readableBytes)
        nettyResponse.setHeader(CONNECTION, KEEP_ALIVE)
      }
      
      headers foreach { pair => nettyResponse.setHeader(pair._1, pair._2) }
        
      val channel = e.getChannel
      val future = channel.write(nettyResponse)
      
      if (!request.keepAlive || !HttpHeaders.isKeepAlive(nettyResponse)) {
        future.addListener(ChannelFutureListener.CLOSE)
      }
          
      log(request, response)
    } onFailure {
      case t:Throwable => throw t
    }
  }

  var application: (Request) => Future[Response] = { request =>
    Promise.successful(Response(ServiceUnavailable))
  }  

  def setApplication(newApplication: (Request) => Future[Response]) { 
    application = newApplication 
  }
  
  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getChannel.close
  }
}
