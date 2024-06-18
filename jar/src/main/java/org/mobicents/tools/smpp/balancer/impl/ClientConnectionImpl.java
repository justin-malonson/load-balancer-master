/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2015, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.tools.smpp.balancer.impl;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLEngine;

import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.util.internal.ConcurrentHashMap;
import org.mobicents.tools.heartbeat.api.Node;
import org.mobicents.tools.sip.balancer.BalancerRunner;
import org.mobicents.tools.sip.balancer.InvocationContext;
import org.mobicents.tools.sip.balancer.KeySmpp;
import org.mobicents.tools.smpp.balancer.api.ClientConnection;
import org.mobicents.tools.smpp.balancer.core.BalancerDispatcher;
import org.mobicents.tools.smpp.balancer.timers.ServerTimerResponse;
import org.mobicents.tools.smpp.balancer.timers.ServerTimerConnectionCheck;
import org.mobicents.tools.smpp.balancer.timers.TimerData;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.channel.SmppChannelConstants;
import com.cloudhopper.smpp.channel.SmppSessionPduDecoder;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BindReceiver;
import com.cloudhopper.smpp.pdu.BindTransceiver;
import com.cloudhopper.smpp.pdu.BindTransmitter;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.Unbind;
import com.cloudhopper.smpp.ssl.SslConfiguration;
import com.cloudhopper.smpp.ssl.SslContextFactory;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoder;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoderContext;
import com.cloudhopper.smpp.transcoder.PduTranscoder;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;

/**
 * @author Konstantin Nosach (kostyantyn.nosach@telestax.com)
 */

public class ClientConnectionImpl implements ClientConnection{
	private static final Logger logger = Logger.getLogger(ClientConnectionImpl.class);
	
    private Channel channel;
	private ClientBootstrap clientBootstrap;
    private ClientConnectionHandlerImpl clientConnectionHandler;
    private SmppSessionConfiguration config;
    private Pdu bindPacket;
	private final PduTranscoder transcoder;
    private ClientState clientState=ClientState.INITIAL;
    private AtomicInteger lastSequenceNumberSent = new AtomicInteger(0);

	private BalancerDispatcher lbClientListener;
    private Long sessionId;
 	private Map<Integer, TimerData> packetMap =  new ConcurrentHashMap <Integer, TimerData>();
 	private Map<Integer, Integer> sequenceMap =  new ConcurrentHashMap <Integer, Integer>();
    private ScheduledExecutorService monitorExecutor;
    private Node node;
    private long timeoutResponse;
    private boolean isEnquireLinkSent;
    private InvocationContext invocationContext;
    
    //private ScheduledFuture<?> connectionCheckServerSideTimer;    
    private ServerTimerConnectionCheck connectionCheck;

	private String localSmppAddress;

    
    public boolean isEnquireLinkSent() {
		return isEnquireLinkSent;
	}
	public SmppSessionConfiguration getConfig() {
		return config;
	}
    public ClientState getClientState() {
		return clientState;
	}
    public void setClientState(ClientState clientState) {
		this.clientState = clientState;
	}
    public Long getSessionId() {
		return sessionId;
	}

    public enum ClientState 
    {    	
    	INITIAL, OPEN, BINDING, BOUND, REBINDING, UNBINDING, CLOSED    	
    }
    
	public  ClientConnectionImpl(Long sessionId,SmppSessionConfiguration config, BalancerDispatcher clientListener, ScheduledExecutorService monitorExecutor, 
			BalancerRunner balancerRunner, Pdu bindPacket, Node node) 
	{

		  this.node = node;
		  this.bindPacket = bindPacket;
		  this.timeoutResponse = balancerRunner.balancerContext.lbConfig.getSmppConfiguration().getTimeoutResponse();
		  this.monitorExecutor = monitorExecutor;
		  this.sessionId = sessionId;
		  this.config = config;
		  this.localSmppAddress = balancerRunner.balancerContext.lbConfig.getSmppConfiguration().getSmppInternalHost();
		  if (this.localSmppAddress == null || this.localSmppAddress.equals("")) {
			  this.localSmppAddress = balancerRunner.balancerContext.lbConfig.getSmppConfiguration().getSmppHost();
		  }
		  this.transcoder = new DefaultPduTranscoder(new DefaultPduTranscoderContext());
		  this.lbClientListener=clientListener;
		  this.clientConnectionHandler = new ClientConnectionHandlerImpl(this);	
          this.clientBootstrap = new ClientBootstrap(new NioClientSocketChannelFactory());
          this.clientBootstrap.getPipeline().addLast(SmppChannelConstants.PIPELINE_SESSION_PDU_DECODER_NAME, new SmppSessionPduDecoder(transcoder));
          this.clientBootstrap.getPipeline().addLast(SmppChannelConstants.PIPELINE_CLIENT_CONNECTOR_NAME, this.clientConnectionHandler); 	
          this.invocationContext = balancerRunner.getLatestInvocationContext();
	}

	@Override
	public Boolean connect() {
		ChannelFuture channelFuture = null;
		try 
		{
			if(logger.isDebugEnabled())
				logger.debug("LB trying to connect to server " + config.getHost() + " " + config.getPort());
			
			channelFuture = clientBootstrap.connect(new InetSocketAddress(config.getHost(), config.getPort()), new InetSocketAddress(localSmppAddress, 0)).sync();
			channel = channelFuture.getChannel();
			
			if (config.isUseSsl()) 
	          {
	      	    SslConfiguration sslConfig = config.getSslConfiguration();
	      	    if (sslConfig == null) throw new IllegalStateException("sslConfiguration must be set");
	      	    try 
	      	    {
	      	    	SslContextFactory factory = new SslContextFactory(sslConfig);
	      	    	SSLEngine sslEngine = factory.newSslEngine();
	      	    	sslEngine.setUseClientMode(true);
	      	    	channel.getPipeline().addFirst(SmppChannelConstants.PIPELINE_SESSION_SSL_NAME, new SslHandler(sslEngine));
	      	    } 
	      	    catch (Exception e) 
	      	    {
	      	    	logger.error("Unable to create SSL session: " + e.getMessage(), e);
	      	    	
	      	    }
	          }

		} catch (Exception ex) 
		{
			return false;
		}

		if(clientState!=ClientState.REBINDING)
		clientState = ClientState.OPEN;

		return true;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void bind()
	{
		 BaseBind packet = null;
	        if (config.getType() == SmppBindType.TRANSCEIVER) 
	        	packet = new BindTransceiver();
	        else if (config.getType() == SmppBindType.RECEIVER)
	        	packet = new BindReceiver();
	        else if (config.getType() == SmppBindType.TRANSMITTER)
	        	packet = new BindTransmitter();
	       

	        packet.setSystemId(config.getSystemId());
	        packet.setPassword(config.getPassword());
	        packet.setSystemType(config.getSystemType());
	        packet.setInterfaceVersion(config.getInterfaceVersion());
	        packet.setAddressRange(config.getAddressRange());
	        packet.setSequenceNumber(lastSequenceNumberSent.incrementAndGet());
  
	        ChannelBuffer buffer = null;
			try {
				buffer = transcoder.encode(packet);
				
			} catch (UnrecoverablePduException e) {			
				logger.error("Encode error: ", e);
			} catch(RecoverablePduException e) {
				logger.error("Encode error: ", e);
			}
			if(clientState!=ClientState.REBINDING)
			    clientState=ClientState.BINDING;
			if(logger.isDebugEnabled())
				logger.debug("LB trying to bind to server " + config.getHost() + " " + config.getPort() + ": client state " + clientState);
			channel.write(buffer);
	}

	@Override
	public void packetReceived(Pdu packet) 
	{
		switch (clientState) {

		case INITIAL:
		case OPEN:
			logger.error("LB received packet ("+packet+") in initial or open state from " + channel.getRemoteAddress().toString()+" sessionId : " +sessionId);
			break;
		case BINDING:
			Boolean correctPacket = false;
			switch (config.getType()) {
			case TRANSCEIVER:
				if (packet.getCommandId() == SmppConstants.CMD_ID_BIND_TRANSCEIVER_RESP)
					correctPacket = true;
				break;
			case RECEIVER:
				if (packet.getCommandId() == SmppConstants.CMD_ID_BIND_RECEIVER_RESP)
					correctPacket = true;
				break;
			case TRANSMITTER:
				if (packet.getCommandId() == SmppConstants.CMD_ID_BIND_TRANSMITTER_RESP)
					correctPacket = true;
				break;

			}

			if (!correctPacket)
				logger.error("Received invalid packet in binding state, packet type: " + packet.getName());
			else {
				if (packet.getCommandStatus() == SmppConstants.STATUS_OK) 
				{
					if(logger.isDebugEnabled())
						logger.debug("LB received bind response (" + packet + ") from " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
					
					clientState = ClientState.BOUND;
					lbClientListener.bindSuccesfull(sessionId, packet);

				} else {
					logger.error("Binding to " + channel.getRemoteAddress().toString() + " is unsuccesful. sessionId" + sessionId + " , error code: " + packet.getCommandStatus());
					lbClientListener.bindFailed(sessionId, packet);
					closeChannel();
					clientState = ClientState.CLOSED;
				}
			}
			break;
			
		case BOUND:
			correctPacket = false;
			switch (packet.getCommandId()) {
			
			case SmppConstants.CMD_ID_CANCEL_SM_RESP:
			case SmppConstants.CMD_ID_DATA_SM_RESP:
			case SmppConstants.CMD_ID_QUERY_SM_RESP:
			case SmppConstants.CMD_ID_REPLACE_SM_RESP:
			case SmppConstants.CMD_ID_SUBMIT_SM_RESP:
			case SmppConstants.CMD_ID_SUBMIT_MULTI_RESP:
				if(logger.isDebugEnabled())
					logger.debug("LB received SMPP response (" + packet + ") from " + channel.getRemoteAddress().toString() + ". sessionId: " + sessionId);
				
				Integer originalSequence=sequenceMap.remove(packet.getSequenceNumber());
				if(originalSequence!=null)
				{
					packet.setSequenceNumber(originalSequence);
					correctPacket = true;
					this.lbClientListener.smppEntityResponse(sessionId, packet);
				}
				break;
			case SmppConstants.CMD_ID_GENERIC_NACK:
				if(logger.isDebugEnabled())
					logger.debug("LB received generic nack (" + packet + ") from " + channel.getRemoteAddress().toString() +". sessionId : " + sessionId);
				
				correctPacket = true;
				this.lbClientListener.smppEntityResponse(sessionId, packet);
				break;
			case SmppConstants.CMD_ID_ENQUIRE_LINK_RESP:
				if(logger.isDebugEnabled())
					logger.debug("LB received enquire_link response from " + channel.getRemoteAddress().toString() + ". session ID : "+ sessionId);
				
				correctPacket = true;
				isEnquireLinkSent = false;
				connectionCheck.cancel();
				//connectionCheckServerSideTimer.cancel(false);
				this.lbClientListener.enquireLinkReceivedFromServer(sessionId);				
				break;
			case SmppConstants.CMD_ID_DATA_SM:
			case SmppConstants.CMD_ID_DELIVER_SM:
				if(logger.isDebugEnabled())
					logger.debug("LB received SMPP request (" + packet + ") from " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
				
				correctPacket = true;
				ServerTimerResponse response=new ServerTimerResponse(this ,packet);
				packetMap.put(packet.getSequenceNumber(), new TimerData(packet, monitorExecutor.schedule(response,timeoutResponse,TimeUnit.MILLISECONDS),response));				
				this.lbClientListener.smppEntityRequestFromServer(sessionId, packet);				
				break;
			case SmppConstants.CMD_ID_ENQUIRE_LINK:
				if(logger.isDebugEnabled())
					logger.debug("LB received enquire_link request from " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
				
				correctPacket = true;
				EnquireLinkResp resp=new EnquireLinkResp();
				resp.setSequenceNumber(packet.getSequenceNumber());
				sendSmppResponse(resp);
				break;		
			case SmppConstants.CMD_ID_UNBIND:
				if(logger.isDebugEnabled())
					logger.debug("LB received unbind request from " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
				
				correctPacket = true;
				response=new ServerTimerResponse(this ,packet);
				packetMap.put(packet.getSequenceNumber(), new TimerData(packet, monitorExecutor.schedule(response,timeoutResponse,TimeUnit.MILLISECONDS),response));
				
				lbClientListener.unbindRequestedFromServer(sessionId, packet);
				clientState = ClientState.UNBINDING;
				break;

			}
			if (!correctPacket)
			{
				logger.error("LB received invalid packet in bound state. sessionId : " + sessionId + ". packet : " + packet);
			}
			break;
			
		case REBINDING:
			
            switch (packet.getCommandId()) 
            {
			case SmppConstants.CMD_ID_BIND_RECEIVER_RESP:
			case SmppConstants.CMD_ID_BIND_TRANSCEIVER_RESP:
			case SmppConstants.CMD_ID_BIND_TRANSMITTER_RESP:
				if (packet.getCommandStatus() == SmppConstants.STATUS_OK)
				{
					if(logger.isDebugEnabled())
						logger.debug("Connection reconnected to " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
					
				    this.lbClientListener.reconnectSuccesful(sessionId);
				    clientState = ClientState.BOUND;
				    if(invocationContext.activeNodeKey!=null)
						invocationContext.activeNodeKey = new KeySmpp((Node) new ArrayList(invocationContext.smppNodeMap.values()).get(0));
				}else
				{
					logger.debug("Reconnection to client unsuccessful. client session ID : " + sessionId + ". LB will close session!");
					this.lbClientListener.unbindRequestedFromServer(sessionId, new Unbind());
				}
            }
			break;
			
		case UNBINDING:
			correctPacket = false;

			if (packet.getCommandId() == SmppConstants.CMD_ID_UNBIND_RESP)
				correctPacket = true;

			if (!correctPacket)
				logger.error("LB received invalid packet (" + packet + ") form "+channel.getRemoteAddress().toString()+" in unbinding state. sessionId : " + sessionId);
			else 
			{
				if(logger.isDebugEnabled())
					logger.debug("LB received unbind response form  " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
				
				Integer originalSequence=sequenceMap.remove(packet.getSequenceNumber());
				if(originalSequence!=null)
				{
					packet.setSequenceNumber(originalSequence);
					this.lbClientListener.unbindSuccesfull(sessionId, packet);
				}
				
				sequenceMap.clear();
				packetMap.clear();
				closeChannel();
				clientState = ClientState.CLOSED;
			}
			break;
		case CLOSED:
			logger.error("LB received packet ("+packet+") in closed state from " + channel.getRemoteAddress().toString()+" sessionId : " +sessionId);
			break;
		}
	}

	@Override
	public void sendUnbindRequest(Pdu packet)
	{		
		Integer currSequence=lastSequenceNumberSent.incrementAndGet();
		sequenceMap.put(currSequence, packet.getSequenceNumber());
		packet.setSequenceNumber(currSequence);
		
		ChannelBuffer buffer = null;
		try {
			buffer = transcoder.encode(packet);
			
		} catch (UnrecoverablePduException e ) {
			logger.error("Encode error: ", e);
		} catch (RecoverablePduException e){
			logger.error("Encode error: ", e);
		}
		clientState = ClientState.UNBINDING;
		if(logger.isDebugEnabled())
			logger.debug("LB sent unbind request (" + packet + ") to " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
		
		channel.write(buffer);
		
	}

	@Override
	public void sendSmppRequest(Pdu packet) 
	{		
		Integer currSequence=lastSequenceNumberSent.incrementAndGet();
		sequenceMap.put(currSequence, packet.getSequenceNumber());
		packet.setSequenceNumber(currSequence);
		
		ChannelBuffer buffer = null;
		try {
			buffer = transcoder.encode(packet);
			
		} catch (UnrecoverablePduException e) {
			logger.error("Encode error: ", e);
		} catch(RecoverablePduException e){
			logger.error("Encode error: ", e);
		}
		if(logger.isDebugEnabled())
			logger.debug("LB sent SMPP request (" + packet + ") to " + channel.getRemoteAddress().toString() + ". session ID : " + sessionId);
		
		channel.write(buffer);
	}

	@Override
	public void sendSmppResponse(Pdu packet) 
	{
		if(packetMap.containsKey(packet.getSequenceNumber()))
		{
			TimerData data=packetMap.remove(packet.getSequenceNumber());
			if(data!=null)
			{
				data.getRunnable().cancel();
				data.getScheduledFuture().cancel(false);
			}
		}
		
		ChannelBuffer buffer = null;
		try {
			buffer = transcoder.encode(packet);
			
		} catch (UnrecoverablePduException e) {
			logger.error("Encode error: ", e);
		} catch(RecoverablePduException e){
			logger.error("Encode error: ", e);
		}
		if(logger.isDebugEnabled())
			logger.debug("LB sent SMPP response (" + packet + ") to " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
		
		channel.write(buffer);
	}

	@Override
     public void sendUnbindResponse(Pdu packet) {
		if(packetMap.containsKey(packet.getSequenceNumber()))
		{
			TimerData data=packetMap.remove(packet.getSequenceNumber());
			if(data!=null)
			{
				data.getRunnable().cancel();
				data.getScheduledFuture().cancel(false);
			}
		}
		ChannelBuffer buffer = null;
		try {
				buffer = transcoder.encode(packet);
				
			} catch (UnrecoverablePduException e) {
				logger.error("Encode error: ", e);
			} catch(RecoverablePduException e){
				logger.error("Encode error: ", e);
			}
		    clientState = ClientState.CLOSED;
		    sequenceMap.clear();
			packetMap.clear();
			if(logger.isDebugEnabled())
				logger.debug("LB sent unbind response (" + packet + ") to " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
			
			channel.write(buffer);
			closeChannel();
		
	}

	@Override
	public void rebind() {
		if(logger.isDebugEnabled())
			logger.debug("LB tried to rebind to client " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
		if(invocationContext.activeNodeKey!=null)
			invocationContext.activeNodeKey = new KeySmpp((Node) new ArrayList(invocationContext.smppNodeMap.values()).get(0));
		clientState = ClientState.REBINDING;		
		this.lbClientListener.connectionLost(sessionId, bindPacket, node);
		
	}

	@Override
	public void requestTimeout(Pdu packet) {
		if (!packetMap.containsKey(packet.getSequenceNumber()))
			if(logger.isDebugEnabled())
				logger.debug("<<requestTimeout>> LB received SMPP response from client in time for server " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
		else 
		{
			if(logger.isDebugEnabled())
				logger.debug("<<requestTimeout>> We did NOT take SMPP response in time from client with sessionId : " + sessionId);
			
			lbClientListener.getNotRespondedPackets().incrementAndGet();
			packetMap.remove(packet.getSequenceNumber());
			PduResponse pduResponse = ((PduRequest<?>) packet).createResponse();
			pduResponse.setCommandStatus(SmppConstants.STATUS_SYSERR);
			sendSmppResponse(pduResponse);
		}
	}

	@Override
	public void generateEnquireLink() 
	{		
		Pdu packet = new EnquireLink();
		packet.setSequenceNumber(lastSequenceNumberSent.incrementAndGet());
		
		ChannelBuffer buffer = null;
		try {
			buffer = transcoder.encode(packet);
			
		} catch (UnrecoverablePduException e) {
			logger.error("Encode error: ", e);
		} catch(RecoverablePduException e){
			logger.error("Encode error: ", e);
		}
		isEnquireLinkSent = true;
		connectionCheck=new ServerTimerConnectionCheck(this);
		//connectionCheckServerSideTimer = monitorExecutor.schedule(connectionCheck,timeoutConnectionCheckServerSide,TimeUnit.MILLISECONDS);
		if(logger.isDebugEnabled())
			logger.debug("LB sent enquire_link to " + channel.getRemoteAddress().toString() + ". sessionId : " + sessionId);
		
		channel.write(buffer);
	}

	@Override
	public void closeChannel() 
	{
		if(channel.getPipeline().getLast()!=null)
			channel.getPipeline().removeLast();
		
		channel.close();		
	}

	@Override
	public void connectionCheckClientSide() 
	{
		rebind();		
	}
	@Override
	public void sendSmppRequest(Long sessionId, Pdu packet) {
		// TODO Auto-generated method stub
		
	}
	@Override
	public void enquireLinkTimerCheck() {
		// TODO Auto-generated method stub
		
	}
	
}