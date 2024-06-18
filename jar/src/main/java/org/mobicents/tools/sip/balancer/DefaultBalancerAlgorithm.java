/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.tools.sip.balancer;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.mobicents.tools.configuration.LoadBalancerConfiguration;
import org.mobicents.tools.heartbeat.api.Node;


public abstract class DefaultBalancerAlgorithm implements BalancerAlgorithm {
	
	private static final Logger logger = Logger.getLogger(DefaultBalancerAlgorithm.class.getCanonicalName());
	protected Properties properties;
	protected BalancerContext balancerContext;
	protected InvocationContext invocationContext;
	protected Iterator<Entry<KeySip, Node>> ipv4It = null;
	protected Iterator<Entry<KeySip, Node>> ipv6It = null;
	protected Iterator<Node> httpRequestIterator = null;
	protected LoadBalancerConfiguration lbConfig; 
	protected ArrayList <Node> cycleListIpV4 = new ArrayList<Node>();
	protected ArrayList <Node> cycleListIpV6 = new ArrayList<Node>();
	protected AtomicInteger currentItemIndexIpV4 = new AtomicInteger(0);
	protected AtomicInteger currentItemIndexIpV6 = new AtomicInteger(0);
	protected AtomicLong cycleStartTimeIpV6 = new AtomicLong(0);
	protected AtomicLong cycleStartTimeIpV4 = new AtomicLong(0);
	private Semaphore semaphore = new Semaphore(1);

	public LoadBalancerConfiguration getConfiguration() {
		return lbConfig;
	}

	public void setConfiguration(LoadBalancerConfiguration lbConfig) {
		this.lbConfig = lbConfig;
	}
	
	public void setInvocationContext(InvocationContext ctx) {
		this.invocationContext = ctx;
	}
	
	public InvocationContext getInvocationContext() {
		return invocationContext;
	}

	public BalancerContext getBalancerContext() {
		return balancerContext;
	}

	public void processInternalRequest(Request request) {
		
	}
	public void configurationChanged() {
		
	}
	
	private void cycle(boolean isIpv6)
	{
		try {
			semaphore.acquire();
		} catch (InterruptedException e) 
		{
			logger.error("Semaphore acquire exception : " + e.getMessage());
		}

		Long currTime;
		if(isIpv6)
			currTime=cycleStartTimeIpV6.get();
		else
			currTime=cycleStartTimeIpV4.get();
		
		if(System.currentTimeMillis() > (lbConfig.getSipConfiguration().getTrafficRampupCyclePeriod() + currTime))
		{
			ArrayList<Node> currList= new ArrayList<Node>();
			for(Entry<KeySip, Node> entry : invocationContext.sipNodeMap(isIpv6).entrySet())
			{
				Node currNode = entry.getValue();
				if(currNode.obtainWeightIndex()<lbConfig.getSipConfiguration().getMaxWeightIndex())
					currNode.incrementWeightIndex();

				for(int i = 0; i < currNode.obtainWeightIndex(); i++)
					currList.add(currNode);				
			}

			if(isIpv6)
			{
				if(currentItemIndexIpV6.get()>cycleListIpV6.size())
					currentItemIndexIpV6.set(0);
				
				cycleListIpV6 = currList;
			}
			else
			{
				if(currentItemIndexIpV4.get()>cycleListIpV4.size())
					currentItemIndexIpV4.set(0);
				
				cycleListIpV4 = currList;
			}
			
			
			if(isIpv6)
				cycleStartTimeIpV6.set(System.currentTimeMillis());
			else
				cycleStartTimeIpV4.set(System.currentTimeMillis());						
		}
		semaphore.release();
		
	}
	
	protected synchronized Node getNextRampUpNode(boolean isIpV6)
	{
		if(isIpV6)
		{
			if(currentItemIndexIpV6.get() < cycleListIpV6.size())
				cycle(isIpV6);

			return cycleListIpV6.get(currentItemIndexIpV6.getAndIncrement()%cycleListIpV6.size());
		}
		else
		{
			if(currentItemIndexIpV4.get() < cycleListIpV4.size())
				cycle(isIpV6);

			
			return cycleListIpV4.get(currentItemIndexIpV4.getAndIncrement()%cycleListIpV4.size());
		}
		
	}
	
	public synchronized Node processHttpRequest(HttpRequest request) {
		if(invocationContext.sipNodeMap(false).size()>0) {
			String instanceId = getInstanceId(request);
			if(instanceId!=null)
			{
				Node node = getNodeByInstanceId(instanceId);
				if(node!=null)
					return node;
			}
			
			String httpSessionId = null;
			httpSessionId = getUrlParameters(request.getUri()).get("jsessionid");
			
			if(httpSessionId == null) 
				httpSessionId = getParameterFromCookie(request, "jsessionid");
				
			if(httpSessionId != null) {
				int indexOfDot = httpSessionId.lastIndexOf('.');
				if(indexOfDot>0 && indexOfDot<httpSessionId.length()) {
					String jvmRoute = httpSessionId.substring(indexOfDot + 1);
					Node node = balancerContext.jvmRouteToSipNode.get(jvmRoute);
					
					if(node != null) {
						if(invocationContext.sipNodeMap(false).containsValue(node)) {
							return node;
						}
					}
				}
			}
			if(logger.isDebugEnabled())
				logger.debug("LB will send request to node accordingly RR algorithm");
			if(httpRequestIterator==null)
			{
				httpRequestIterator = invocationContext.sipNodeMap(false).values().iterator();
			}
			Node node = null;
			int count = invocationContext.sipNodeMap(false).size();
			while(count>0)
			{
				while(httpRequestIterator.hasNext() && count > 0)
				{
					node = httpRequestIterator.next();
					if(node!=null&&!node.isGracefulShutdown()&&!node.isBad())
					{
						return node;
					}
					else
					{
						count--;
					}
				}
				if(!httpRequestIterator.hasNext())
					httpRequestIterator = invocationContext.sipNodeMap(false).values().iterator();
			}
			return null;
		} else {
			String unavailaleHost = getConfiguration().getHttpConfiguration().getUnavailableHost();
			if(unavailaleHost != null && unavailaleHost != "") {
				Node node = new Node(unavailaleHost, unavailaleHost);
				node.getProperties().put("httpPort", "" + 80);
				return node;
			} else {
				return null;
			}
		}
	}
	
	@Override
	public void proxyMessage(ChannelHandlerContext ctx, MessageEvent e){}
	
	@Override
	public boolean blockInternalRequest(Request request){
	    return false;
	}
	
	public Node processAssignedExternalRequest(Request request, Node assignedNode) {
		return assignedNode;
	}

	private HashMap<String,String> getUrlParameters(String url) {
		HashMap<String,String> parameters = new HashMap<String, String>();
    	int start = url.lastIndexOf('?');
    	if(start>0 && url.length() > start +1) {
    		url = url.substring(start + 1);
    	} else {
    		return parameters;
    	}
    	String[] tokens = url.split("&");
    	for(String token : tokens) {
    		String[] params = token.split("=");
    		if(params.length<2) {
    			parameters.put(token, "");
    		} else {
    			parameters.put(params[0], params[1]);
    		}
    	}
    	return parameters;
    }
	
	protected String getInstanceId(HttpRequest request)
	{
		String url = request.getUri();
		String[] tokens = url.split("/");
		if(tokens.length>6&&tokens[3].equals("Accounts")&&tokens[5].startsWith("Calls"))
		{
			if(tokens[6].split("-").length>1)
			{
				String instanceId = tokens[6].substring(0,34);
				return instanceId;
			}
		}	
		return null;
	}
	
	private String getParameterFromCookie(HttpRequest request, String parameter){
		
			CookieDecoder cookieDocoder = new CookieDecoder();
			String cookieString = request.getHeader("Cookie");
			if(cookieString != null) {
				Set<Cookie> cookies = cookieDocoder.decode(cookieString);
				Iterator<Cookie> cookieIterator = cookies.iterator();
				while(cookieIterator.hasNext()) {
					Cookie c = cookieIterator.next();
					if(c.getName().equalsIgnoreCase("jsessionid")) {
						return c.getValue();
					}
				}
			}
		return null;
	}
	
	public void processInternalResponse(Response response,Boolean isIpV6) {
		
	}
	
	public void processExternalResponse(Response response,Boolean isIpV6) {
		
	}
	
	public void start() {
		
	}
	
	public void stop() {
		
	}
	
	public void nodeAdded(Node node) 
	{
		ipv4It = null;
		ipv6It = null;
		httpRequestIterator = null;
		if(lbConfig.getSipConfiguration().getTrafficRampupCyclePeriod()!=null&&lbConfig.getSipConfiguration().getMaxWeightIndex()!=null)
		{
			boolean isIpV6 = false;
			try {
				isIpV6 = InetAddress.getByName(node.getIp()) instanceof Inet6Address;
			} catch (UnknownHostException e) {

				logger.error("A Problem happened during the determination isIpV6 for ip : " + node.getIp() +". So it will be default false", e);
			}
			
			if(isIpV6)
				cycleStartTimeIpV6.set(0);
			else
				cycleStartTimeIpV4.set(0);
			
			cycle(isIpV6);
		}
	
	}

	public void nodeRemoved(Node node) 
	{
		ipv4It = null;
		ipv6It = null;
		httpRequestIterator = null;
		if(lbConfig.getSipConfiguration().getTrafficRampupCyclePeriod()!=null&&lbConfig.getSipConfiguration().getMaxWeightIndex()!=null)
		{
			boolean isIpV6 = false;
			try {
				isIpV6 = InetAddress.getByName(node.getIp()) instanceof Inet6Address;
			} catch (UnknownHostException e) {

				logger.error("A Problem happened during the determination isIpV6 for ip : " + node.getIp() +". So it will be default false", e);
			}
			
			if(isIpV6)
				cycleStartTimeIpV6.set(0);
			else
				cycleStartTimeIpV4.set(0);
			
			cycle(isIpV6);
		}
	}
	
	public void jvmRouteSwitchover(String fromJvmRoute, String toJvmRoute) {
		
	}
	
	public void assignToNode(String id, Node node) {
		
	}	
	
	protected Node getNodeByInstanceId(String instanceId)
	{
		if(logger.isDebugEnabled())
			logger.debug("Node by instanceId("+instanceId+") getting");
		Node node = invocationContext.httpNodeMap.get(new KeyHttp(instanceId));
		if(node!=null&&!node.isBad()&&!node.isGracefulShutdown())
		{
			return node;
		}
		else
		{
			logger.warn("instanceId exists in HTTP request but doesn't match to any node or Node is bad or graceful shudown."
					+ " Node: "+ node);
			return null;
		}
	}
	
}
