package com.chicm.cmraft.rpc;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.chicm.cmraft.core.RaftRpcService;
import com.chicm.cmraft.protobuf.generated.RaftProtos.ResponseHeader;
import com.chicm.cmraft.util.BlockingHashMap;
import com.google.protobuf.BlockingService;
import com.google.protobuf.Message;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Message.Builder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;

public class ClientChannelHandler extends ChannelInitializer<Channel>  {
  static final Log LOG = LogFactory.getLog(ClientChannelHandler.class);
  private static final int MAX_PACKET_SIZE = 1024*1024*100;
  private ChannelHandlerContext activeCtx;
  private BlockingHashMap<Integer, RpcCall> responseMap;
  //private long startTime = System.currentTimeMillis();
  
  public ClientChannelHandler(BlockingHashMap<Integer, RpcCall> responseMap) {
    this.responseMap = responseMap;
  }
  
  @Override
  protected void initChannel(Channel ch) throws Exception {
    ch.pipeline().addLast("FrameDecoder", new LengthFieldBasedFrameDecoder(MAX_PACKET_SIZE,0,4,0,4)); 
    ch.pipeline().addLast("FrameEncoder", new LengthFieldPrepender(4));
    ch.pipeline().addLast("MessageDecoder", new RpcResponseDecoder() );
    ch.pipeline().addLast("MessageEncoder", new RpcRequestEncoder());
    ch.pipeline().addLast("ClientHandler", new RpcResponseHandler());
    LOG.info("initChannel");
  }
  
  public ChannelHandlerContext getCtx() {
    return activeCtx;
  }
  
  class RpcResponseHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      
      RpcCall call = (RpcCall) msg;
      LOG.debug("client channel read, callid: " + call.getCallId());
      
      responseMap.put(call.getCallId(), call);
    }
    
    @Override
    public void channelActive(final ChannelHandlerContext ctx) { // (1)
      activeCtx = ctx;
      LOG.info("Client Channel Active");
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
  }
  
  class RpcResponseDecoder extends MessageToMessageDecoder<ByteBuf> {
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out)       
        throws Exception {
      //System.out.println("size:" + msg.capacity());
      
      ByteBufInputStream in = new ByteBufInputStream(msg);

      ResponseHeader.Builder hbuilder = ResponseHeader.newBuilder();
      hbuilder.mergeDelimitedFrom(in);
      ResponseHeader header = hbuilder.build();
      //System.out.println("header:" + header);

      BlockingService service = RaftRpcService.create().getService();
      
      MethodDescriptor md = service.getDescriptorForType().findMethodByName(header.getResponseName());
      Builder builder = service.getResponsePrototype(md).newBuilderForType();
      Message body = null;
      if (builder != null) {
        if(builder.mergeDelimitedFrom(in)) {
          body = builder.build();
          //System.out.println("body parsed:" + body);
          
        } else {
          //System.out.println("parse failed");
        }
      }
      RpcCall call = new RpcCall(header.getId(), header, body, md);
        //System.out.println("Parse Rpc request from socket: " + call.getCallId() 
        //  + ", takes" + (System.currentTimeMillis() -t) + " ms");

      out.add(call);
    }
  }
  
  class RpcRequestEncoder extends MessageToMessageEncoder<RpcCall> {
    @Override
    protected  void encode(ChannelHandlerContext ctx,  RpcCall call, List<Object> out) throws Exception {
      //System.out.println("RpcMessageEncoder");
      int totalSize = PacketUtils.getTotalSizeofMessages(call.getHeader(), call.getMessage());
      ByteBuf encoded = ctx.alloc().buffer(totalSize);
      ByteBufOutputStream os = new ByteBufOutputStream(encoded);
      try {
        call.getHeader().writeDelimitedTo(os);
        if (call.getMessage() != null)  {
          call.getMessage().writeDelimitedTo(os);
        }
        out.add(encoded);
      } catch(Exception e) {
        e.printStackTrace(System.out);
      }
    }
  }
}