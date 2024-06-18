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

package org.mobicents.tools.sip.balancer.scalability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import javax.sip.ListeningPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mobicents.tools.configuration.LoadBalancerConfiguration;
import org.mobicents.tools.heartbeat.impl.HeartbeatConfigHttp;
import org.mobicents.tools.sip.balancer.AppServer;
import org.mobicents.tools.sip.balancer.BalancerRunner;
import org.mobicents.tools.sip.balancer.EventListener;
import org.mobicents.tools.sip.balancer.HeaderConsistentHashBalancerAlgorithm;
import org.mobicents.tools.sip.balancer.UDPPacketForwarder;
import org.mobicents.tools.sip.balancer.operation.Shootist;

public class SprayingTwoLoadBalancersTest {
	int numBalancers = 2;
	BalancerRunner[] balancers = new BalancerRunner[numBalancers];
	int numNodes = 10;
	AppServer[] servers = new AppServer[numNodes];
	Shootist shootist;
	AppServer inviteServer,ackServer,byeServer;

	UDPPacketForwarder externalIpLoadBalancer;
	UDPPacketForwarder internalIpLoadBalancer;
	String loadbalancers = "";
	
	private BalancerRunner prepBalancer(int id) {
		BalancerRunner balancer = new BalancerRunner();
		LoadBalancerConfiguration lbConfig = new LoadBalancerConfiguration();
		int heartbeatPort = 2610+id;
		loadbalancers += "127.0.0.1:"+heartbeatPort;
		if(id < balancers.length-1)
			loadbalancers+=",";
		HeartbeatConfigHttp heartbeatConfig = new HeartbeatConfigHttp();
		heartbeatConfig.setHeartbeatPort(heartbeatPort);
		lbConfig.setHeartbeatConfiguration(heartbeatConfig);
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("javax.sip.STACK_NAME", "SipBalancerForwarder" + id);
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setHost("127.0.0.1");
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setHost("127.0.0.1");
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setTcpPort(null);
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setUdpPort(5060+id*100);
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setUdpPort(5065+id*100);
		lbConfig.getHttpConfiguration().setHttpPort(null);
		lbConfig.getSmppConfiguration().setSmppPort(null);
		lbConfig.getSipConfiguration().getAlgorithmConfiguration().setAlgorithmClass(HeaderConsistentHashBalancerAlgorithm.class.getName());
		ArrayList <String> ipLoadBalancerAddressList = new ArrayList<String>();
		ipLoadBalancerAddressList.add("127.0.0.1");
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setIpLoadBalancerAddress(ipLoadBalancerAddressList);
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setIpLoadBalancerAddress(ipLoadBalancerAddressList);
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setIpLoadBalancerUdpPort(9988);
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setIpLoadBalancerUdpPort(9922);
		balancer.start(lbConfig);
		return balancer;
	}

	@Before
	public void setUp() throws Exception {
		shootist = new Shootist();
		String externalIpLBString = "";
		String internalIpLBString = "";
		for(int q=0;q<numBalancers;q++) {
			balancers[q] = prepBalancer(q);
			externalIpLBString += "127.0.0.1:"+5+q+"60,";
			internalIpLBString += "127.0.0.1:"+5+q+"65,";
		}
		for(int q=0;q<servers.length;q++) 
		{
			servers[q] = new AppServer("node" + q,4060+q , "127.0.0.1", 2000, 5060, 5065, "0", ListeningPoint.UDP, 2222+q);
			servers[q].setBalancers(loadbalancers);
			servers[q].start();
		}

		externalIpLoadBalancer = new UDPPacketForwarder(9988, externalIpLBString, "127.0.0.1");
		externalIpLoadBalancer.start();
		internalIpLoadBalancer = new UDPPacketForwarder(9922, internalIpLBString, "127.0.0.1");
		internalIpLoadBalancer.start();
		Thread.sleep(5000);
	}

	@After
	public void tearDown() throws Exception {
		for(int q=0;q<servers.length;q++) 
			servers[q].stop();
		
		externalIpLoadBalancer.stop();
		internalIpLoadBalancer.stop();
		shootist.stop();
		
		for(int q=0;q<numBalancers;q++) {
			balancers[q].stop();
		}
	}


	@Test
	public void testSprayingRoundRobinSIPLBsUASCallConsistentHash() throws Exception {
		
		EventListener failureEventListener = new EventListener() {
			@Override
			public void uasAfterResponse(int statusCode, AppServer source) {


			}

			@Override
			public void uasAfterRequestReceived(String method, AppServer source) {
				if(method.equals("INVITE")) {
					inviteServer = source;
				} else if(method.equals("ACK")) {
					ackServer = source;
				} else {
					byeServer = source;
				}
			}

			@Override
			public void uacAfterRequestSent(String method, AppServer source) {
				// TODO Auto-generated method stub

			}

			@Override
			public void uacAfterResponse(int statusCode, AppServer source) {

			}
		};
		for(AppServer as:servers) as.setEventListener(failureEventListener);
		shootist.peerHostPort="127.0.0.1:9988";
		shootist.callerSendsBye=true;
		shootist.sendInitialInvite();
		//servers[0].sendHeartbeat = false;
		Thread.sleep(12000);
		shootist.sendBye();
		Thread.sleep(4000);
		assertEquals(3, externalIpLoadBalancer.sipMessageWithoutRetrans.size());
		assertSame(inviteServer, byeServer);
		assertSame(inviteServer, ackServer);
		assertNotNull(byeServer);
		assertNotNull(ackServer);
	}
	
	@Test
	public void testSprayingMultipleIndialogMessages() throws Exception {		
		Thread.sleep(1000);
		for(BalancerRunner balancer: balancers){
			balancer.setNodeExpiration(15000);
		}
		shootist.callerSendsBye=true;
		shootist.sendInitialInvite();
		Thread.sleep(10000);
		for(int q=0;q<10;q++){
			shootist.sendMessage();Thread.sleep(600);
		}
		Thread.sleep(2000);
		assertTrue(shootist.responses.size()>10);
	}

}
