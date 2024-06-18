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

package org.mobicents.tools.sip.balancer;

import gov.nist.javax.sip.header.HeaderExt;
import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.message.ResponseExt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sip.ListeningPoint;
import javax.sip.address.SipURI;
import javax.sip.address.TelURL;
import javax.sip.address.URI;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderAddress;
import javax.sip.header.ToHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.mobicents.tools.heartbeat.api.Node;

/**
 * @author Konstantin Nosach (kostyantyn.nosach@telestax.com)
 */

public class UserBasedAlgorithm extends DefaultBalancerAlgorithm {
	private static Logger logger = Logger.getLogger(UserBasedAlgorithm.class.getCanonicalName());

	protected String headerName = "To";
	protected ConcurrentHashMap<String, Node> userToMap = new ConcurrentHashMap<String, Node>();
	protected ConcurrentHashMap<String, Long> headerToTimestamps = new ConcurrentHashMap<String, Long>();
	protected AtomicInteger nextNodeCounter = new AtomicInteger(0);
	protected int maxCallIdleTime = 500;
	protected boolean groupedFailover = false;
	
	protected Timer cacheEvictionTimer = new Timer();
	
	@Override
	public void processInternalRequest(Request request) {
		logger.debug("internal request");
	}
	
	@Override
	public void processInternalResponse(Response response,Boolean isIpV6) {
		Via via = (Via) response.getHeader(Via.NAME);
		String transport = via.getTransport().toLowerCase();
		String host = via.getHost();
		Integer port = via.getPort();
		boolean found = false;
		Node senderNode = (Node) ((ResponseExt)response).getApplicationData();
		
		if(logger.isDebugEnabled()) {
			logger.debug("internal response checking sendernode " + senderNode + " or Via host:port " + host + ":" + port);
		} 
		if(senderNode != null&&invocationContext.sipNodeMap(isIpV6).containsValue(senderNode))
			found = true;
		else if	(invocationContext.sipNodeMap(isIpV6).containsKey(new KeySip(host, port,isIpV6)))
			found = true;
		else if(balancerContext.responsesStatusCodeNodeRemoval.contains(response.getStatusCode()))
			return;

		if(logger.isDebugEnabled()) {
			logger.debug("internal response node found ? " + found);
		}
		if(!found) {
			String headerKey = extractHeaderKey(response);
			
			Node node = userToMap.get(headerKey);
			//if(node == null || !invocationContext.nodes.contains(node)) {
			if(node == null || !invocationContext.sipNodeMap(isIpV6).containsValue(node)) {
				node = selectNewNode(node, headerKey, isIpV6);
				String transportProperty = transport + "Port";
				port = Integer.parseInt(node.getProperties().get(transportProperty));
				if(port == null) throw new RuntimeException("No transport found for node " + node + " " + transportProperty);
				if(logger.isDebugEnabled()) {
					logger.debug("changing via " + via + "setting new values " + node.getIp() + ":" + port);
				}
				try {
					via.setHost(node.getIp());
					via.setPort(port);
				} catch (Exception e) {
					throw new RuntimeException("Error setting new values " + node.getIp() + ":" + port + " on via " + via, e);
				}
				// need to reset the rport for reliable transports
				if(!ListeningPoint.UDP.equalsIgnoreCase(transport)) {
					via.setRPort();
				}				
			}
		}
	}
	
	private String extractHeaderKey(Message message) {
		String headerKey;
		if(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKey.equalsIgnoreCase(ToHeader.NAME))
    	{
    		URI currURI=((HeaderAddress)message.getHeader(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKey)).getAddress().getURI();
    		if(currURI.isSipURI())
    			headerKey = ((SipURI)currURI).getUser();
    		else
    			headerKey = ((TelURL)currURI).getPhoneNumber();
    		
    		if(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKeyExclusionPattern != null && 
    				invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKeyExclusionPattern.matcher(headerKey).matches()) {
    			headerKey = ((HeaderExt) message.getHeader(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityFallbackKey)).getValue();
    		}
    	}
    	else if(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKey.equalsIgnoreCase(FromHeader.NAME)) {
    		headerKey = ((HeaderAddress) message.getHeader(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKey)).getAddress().getURI().toString();
    		
    		if(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKeyExclusionPattern != null && 
    				invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityKeyExclusionPattern.matcher(headerKey).matches()) {
    			headerKey = ((HeaderExt) message.getHeader(invocationContext.balancerAlgorithm.balancerContext.sipHeaderAffinityFallbackKey)).getValue();
    		}
    	}
    	else
    	{
    		headerKey = ((HeaderExt) message.getHeader(balancerContext.sipHeaderAffinityKey)).getValue();
    	}
		return headerKey;
	}
	
	@Override
	public void processExternalResponse(Response response,Boolean isIpV6) {
		Via via = (Via) response.getHeader(Via.NAME);
		String transport = via.getTransport().toLowerCase();
		String host = via.getHost();
		Integer port = via.getPort();
		boolean found = false;

		if(invocationContext.sipNodeMap(isIpV6).containsKey(new KeySip(host, port,isIpV6)))
			found = true;
		if(logger.isDebugEnabled()) {
			logger.debug("external response node found ? " + found);
		}
		if(!found) {
			String headerKey = extractHeaderKey(response);
			
			Node node = userToMap.get(headerKey);
			//if(node == null || !invocationContext.nodes.contains(node)) {
			if(node == null || !invocationContext.sipNodeMap(isIpV6).containsValue(node)) {
				node = selectNewNode(node, headerKey, isIpV6);
				String transportProperty = transport + "Port";
				port = Integer.parseInt(node.getProperties().get(transportProperty));
				if(port == null) throw new RuntimeException("No transport found for node " + node + " " + transportProperty);
				if(logger.isDebugEnabled()) {
					logger.debug("changing via " + via + "setting new values " + node.getIp() + ":" + port);
				}
				try {
					via.setHost(node.getIp());
					via.setPort(port);
				} catch (Exception e) {
					throw new RuntimeException("Error setting new values " + node.getIp() + ":" + port + " on via " + via, e);
				}
				// need to reset the rport for reliable transports
				if(!ListeningPoint.UDP.equalsIgnoreCase(transport)) {
					via.setRPort();
				}
				
			} else {
				String transportProperty = transport + "Port";
				port = Integer.parseInt(node.getProperties().get(transportProperty));
				if(via.getHost().equalsIgnoreCase(node.getIp()) || via.getPort() != port) {
					if(logger.isDebugEnabled()) {
						logger.debug("changing retransmission via " + via + "setting new values " + node.getIp() + ":" + port);
					}
					try {
						via.setHost(node.getIp());
						via.setPort(port);
						via.removeParameter("rport");
						via.removeParameter("received");
					} catch (Exception e) {
						throw new RuntimeException("Error setting new values " + node.getIp() + ":" + port + " on via " + via, e);
					}
					// need to reset the rport for reliable transports
					if(!ListeningPoint.UDP.equalsIgnoreCase(transport)) {
						via.setRPort();
					}
				}
			}
		}
	}

	@Override
	public Node processExternalRequest(Request request, Boolean isIpV6) {
		String headerKey = extractHeaderKey(request);
		Node node = null;
		if (balancerContext.regexMap != null && balancerContext.regexMap.size() != 0) {
			if (logger.isDebugEnabled())
				logger.debug("regexMap is not empty : " + balancerContext.regexMap);

			for (Entry<String, KeySip> entry : balancerContext.regexMap.entrySet()) {
				Pattern r = Pattern.compile(entry.getKey());
				Matcher m = r.matcher(headerKey);
				if (m.find()) {
					node = invocationContext.sipNodeMap(isIpV6).get(entry.getValue());
					if (node != null) {
						if (logger.isDebugEnabled())
							logger.debug("Found node for pattern : " + entry.getKey() + " and key :" + entry.getValue());
						return node;
					} else {
						if (logger.isDebugEnabled())
							logger.debug("Node not found in the map of nodes. It is null. For pattern: " + entry.getKey() + " and key :" + entry.getValue());
					}
				}
			}
		} else {
			node = userToMap.get(headerKey);
			headerToTimestamps.put(headerKey, System.currentTimeMillis());
		}

		if(node == null||invocationContext.sipNodeMap(isIpV6).get(new KeySip(node,isIpV6)).isGracefulShutdown()) { //
			if(lbConfig.getSipConfiguration().getTrafficRampupCyclePeriod()!=null&&lbConfig.getSipConfiguration().getMaxWeightIndex()!=null)
				node = getNextRampUpNode(isIpV6);
			else
				node = nextAvailableNode(isIpV6);

			if(node == null) return null;
			userToMap.put(headerKey, node);
			if(logger.isDebugEnabled()) {
	    		logger.debug("No node found in the affinity map. It is null. We select new node: " + node);
	    	}
		} else {
			//if(!invocationContext.nodes.contains(node)) { // If the assigned node is now dead
			if(!invocationContext.sipNodeMap(isIpV6).containsValue(node)) { // If the assigned node is now dead
				node = selectNewNode(node, headerKey,isIpV6);
			} else { // ..else it's alive and we can route there
				//.. and we just leave it like that
				if(logger.isDebugEnabled()) {
		    		logger.debug("The assigned node in the affinity map is still alive: " + node);
		    	}
			}
		}
		
		return node;
	}
	
	protected Node selectNewNode(Node node, String user,Boolean isIpV6) {
		if(logger.isDebugEnabled()) {
    		logger.debug("The assigned node has died. This is the dead node: " + node);
    	}
		if(groupedFailover) {
			// This will occur very rarely because we re-assign all calls from the dead node in
			// a single operation
			Node oldNode = node;
			node = leastBusyTargetNode(oldNode);
			if(node == null) return null;
			groupedFailover(oldNode, node);
		} else {
			//Boolean isIpV6=LbUtils.isValidInet6Address(node.getIp());        	            							
			if(lbConfig.getSipConfiguration().getTrafficRampupCyclePeriod()!=null&&lbConfig.getSipConfiguration().getMaxWeightIndex()!=null)
				node = getNextRampUpNode(isIpV6);
			else
				node = nextAvailableNode(isIpV6);
			if(node == null) {
				if(logger.isDebugEnabled()) {
		    		logger.debug("no nodes available return null");
		    	}
				return null;
			}
			userToMap.put(user, node);
		}
		
		if(logger.isDebugEnabled()) {
    		logger.debug("So, we must select new node: " + node);
    	}
		return node;
	}
	
	protected synchronized Node nextAvailableNode(Boolean isIpV6) {
		if(invocationContext.sipNodeMap(isIpV6).size() == 0) return null;
		Iterator<Entry<KeySip, Node>> currIt = null; 
		if(isIpV6)
			currIt = ipv6It;
		else
			currIt = ipv4It;
		if(currIt==null)
		{
			currIt = invocationContext.sipNodeMap(isIpV6).entrySet().iterator();
			if(isIpV6)
				 ipv6It = currIt;
			else
				 ipv4It = currIt;
		}
		Entry<KeySip, Node> pair = null;
		int count = invocationContext.sipNodeMap(isIpV6).size();
		while(count>0)
		{
			while(currIt.hasNext() && count > 0)
			{
				pair = currIt.next();
				if(invocationContext.sipNodeMap(isIpV6).containsKey(pair.getKey())
						&&!invocationContext.sipNodeMap(isIpV6).get(pair.getKey()).isGracefulShutdown()
						&&!invocationContext.sipNodeMap(isIpV6).get(pair.getKey()).isBad())
				{
					return pair.getValue();
				}
				else
				{
					count--;
				}
			}
			
			if(!currIt.hasNext())
				currIt = invocationContext.sipNodeMap(isIpV6).entrySet().iterator();
			
			if(isIpV6)
				 ipv6It = currIt;
			else
				 ipv4It = currIt;
		}
		return null;
	}
	
	protected synchronized Node leastBusyTargetNode(Node deadNode) {
		HashMap<Node, Integer> nodeUtilization = new HashMap<Node, Integer>();
		for(Node node : userToMap.values()) {
			Integer n = nodeUtilization.get(node);
			if(n == null) {
				nodeUtilization.put(node, 0);
			} else {
				nodeUtilization.put(node, n+1);
			}
		}
		int minUtil = Integer.MAX_VALUE;
		Node minUtilNode = null;
		for(Node node : nodeUtilization.keySet()) {
			Integer util = nodeUtilization.get(node);
			if(!node.equals(deadNode) && (util < minUtil)) {
				minUtil = util;
				minUtilNode = node;
			}
		}

		logger.info("Least busy node selected " + minUtilNode + " with " + minUtil + " calls");
		
		return minUtilNode;
	}
	
	@Override
	public void stop() {
		this.cacheEvictionTimer.cancel();
	}
	
	@Override
	public void init() {
		if(getConfiguration() != null) {
			Integer maxTimeInCacheString = getConfiguration().getSipConfiguration().getAlgorithmConfiguration().getCallIdAffinityMaxTimeInCache();
			if(maxTimeInCacheString != null) {
				this.maxCallIdleTime = maxTimeInCacheString;
			}
		}
		logger.info("Call Idle Time is " + this.maxCallIdleTime + " seconds. Inactive calls will be evicted.");

		final UserBasedAlgorithm thisAlgorithm = this;
		this.cacheEvictionTimer.schedule(new TimerTask() {

			@Override
			public void run() {
				try {
					synchronized (thisAlgorithm) {
						ArrayList<String> oldCalls = new ArrayList<String>();
						Iterator<String> keys = headerToTimestamps.keySet().iterator();
						while(keys.hasNext()) {
							String key = keys.next();
							long time = headerToTimestamps.get(key);
							if(System.currentTimeMillis() - time > 1000*maxCallIdleTime) {
								oldCalls.add(key);
							}
						}
						for(String key : oldCalls) {
							userToMap.remove(key);
							headerToTimestamps.remove(key);
						}
						if(oldCalls.size()>0) {
							logger.info("Reaping idle calls... Evicted " + oldCalls.size() + " calls.");
						}}
				} catch (Exception e) {
					logger.warn("Failed to clean up old calls. If you continue to se this message frequestly and the memory is growing, report this problem.", e);
				}

			}
		}, 0, 6000);

		if(getConfiguration() != null) {
			this.groupedFailover = getConfiguration().getSipConfiguration().getAlgorithmConfiguration().isCallIdAffinityGroupFailover();
		}
		logger.info("Grouped failover is set to " + this.groupedFailover);
		
	}
	
	@Override
	public void configurationChanged() {
		this.cacheEvictionTimer.cancel();
		this.cacheEvictionTimer = new Timer();
		init();
	}
	
	@Override
	public void assignToNode(String id, Node node) {
		userToMap.put(id, node);
		headerToTimestamps.put(id, System.currentTimeMillis());
	}
	
	@Override
	public void jvmRouteSwitchover(String fromJvmRoute, String toJvmRoute) {
		try {
			Node oldNode = getBalancerContext().jvmRouteToSipNode.get(fromJvmRoute);
			Node newNode = getBalancerContext().jvmRouteToSipNode.get(toJvmRoute);
			if(oldNode != null && newNode != null) {
				int updatedRoutes = 0;
				for(String key : userToMap.keySet()) {
					Node n = userToMap.get(key);
					if(n.equals(oldNode)) {
						userToMap.replace(key, newNode);
						updatedRoutes++;
					}
				}
				if(logger.isInfoEnabled()) {
					logger.info("Switchover occured where fromJvmRoute=" + fromJvmRoute + " and toJvmRoute=" + toJvmRoute + " with " + 
							updatedRoutes + " updated routes.");
				}
			} else {
				if(logger.isInfoEnabled()) {
					logger.info("Switchover failed where fromJvmRoute=" + fromJvmRoute + " and toJvmRoute=" + toJvmRoute);
				}
			}
		} catch (Throwable t) {
			if(logger.isInfoEnabled()) {
				logger.info("Switchover failed where fromJvmRoute=" + fromJvmRoute + " and toJvmRoute=" + toJvmRoute);
				logger.info("This is not a fatal failure, logging the reason for the failure ", t);
			}
		}
	}
	
	
	synchronized public void groupedFailover(Node oldNode, Node newNode) {
		try {
			if(oldNode != null && newNode != null) {
				int updatedRoutes = 0;
				for(String key : userToMap.keySet()) {
					Node n = userToMap.get(key);
					if(n.equals(oldNode)) {
						userToMap.replace(key, newNode);
						updatedRoutes++;
					}
				}
				if(logger.isInfoEnabled()) {
					logger.info("Switchover occured where oldNode=" + oldNode + " and newNode=" + newNode + " with " + 
							updatedRoutes + " updated routes.");
				}
			} else {
				if(logger.isInfoEnabled()) {
					logger.info("Switchover failed where fromJvmRoute=" + oldNode + " and toJvmRoute=" + newNode);
				}
			}
		} catch (Throwable t) {
			if(logger.isInfoEnabled()) {
				logger.info("Switchover failed where fromJvmRoute=" + oldNode + " and toJvmRoute=" + newNode);
				logger.info("This is not a fatal failure, logging the reason for the failure ", t);
			}
		}
	}

	@Override
	public Integer getNumberOfActiveCalls() {
		return userToMap.size();
	}
	
}