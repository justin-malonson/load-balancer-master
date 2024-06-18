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

package org.mobicents.tools.sip.balancer.operation;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Properties;

import javax.sip.ListeningPoint;
import javax.sip.message.Response;

import org.junit.After;
import org.junit.Test;
import org.mobicents.tools.configuration.LoadBalancerConfiguration;
import org.mobicents.tools.sip.balancer.AppServer;
import org.mobicents.tools.sip.balancer.BalancerRunner;
import org.mobicents.tools.sip.balancer.operation.Shootist;

/**
 * @author Konstantin Nosach (kostyantyn.nosach@telestax.com)
 */

public class MediaFailureTest {
	
	BalancerRunner balancer;
	Shootist shootist;
	Properties properties;
	AppServer badServer,goodServer;

	public void setUp() throws Exception
	{
		shootist = new Shootist(ListeningPoint.TCP,5060);
		balancer = new BalancerRunner();
		LoadBalancerConfiguration lbConfig = new LoadBalancerConfiguration();
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.TRACE_LEVEL", "32");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.DEBUG_LOG","logs/sipbalancerforwarderdebug.txt");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.SERVER_LOG","logs/sipbalancerforwarder.xml");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE", "2");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");
		lbConfig.getSipStackConfiguration().getSipStackProperies().setProperty("gov.nist.javax.sip.CANCEL_CLIENT_TRANSACTION_CHECKED", "false");	
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setTcpPort(5060);
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setTcpPort(5065);
		ArrayList <Integer> statusRemoval = new ArrayList <Integer>();
		statusRemoval.add(503);
		lbConfig.getSipConfiguration().setResponseStatusCodeNodeRemoval(statusRemoval);
		lbConfig.getSipConfiguration().setMaxNumberResponsesWithError(2);
		lbConfig.getSipConfiguration().setMaxErrorTime(3000000l);
		balancer.start(lbConfig);
		
		goodServer = new AppServer("node0",4060 , "127.0.0.1", 2000, 5060, 5065, "0", ListeningPoint.TCP, 2223);
		badServer = new AppServer("node1",4061 , "127.0.0.1", 2000, 5060, 5065, "0", ListeningPoint.TCP, false,true);
		goodServer.start();
		Thread.sleep(5000);
		badServer.start();
		Thread.sleep(5000);
	}
	
	@After
	public void tearDown() throws Exception 
	{
		shootist.stop();
		badServer.stop();
		goodServer.stop();
		balancer.stop();
	}
	
	@Test
	public void testMediaFailure() throws Exception
	{
		int serviceAnavaible = 0;
		int okCounter = 0;
		setUp();
		for(int i=0; i<8; i++)
		{
			shootist.sendInitialInvite();
			Thread.sleep(5000);
			if(i==6)
			{
				badServer.stop();
				goodServer.stop();
				Thread.sleep(5000);
				badServer.start();
				Thread.sleep(8000);
			}
		}
		
		for(Response res : shootist.responses)
		{
			if(res.getStatusCode() == Response.SERVICE_UNAVAILABLE)
				serviceAnavaible++;
			if(res.getStatusCode() == Response.OK)
				okCounter++;
		}
		assertEquals(3,serviceAnavaible);
		assertEquals(5,okCounter);
		assertEquals(1,badServer.getTestSipListener().getDialogCount());
		assertEquals(4,goodServer.getTestSipListener().getDialogCount());
		
		
	}
}

