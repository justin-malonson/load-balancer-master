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

package org.mobicents.tools.sip.balancer.performance;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.After;
import org.junit.Before;
import org.mobicents.tools.configuration.LoadBalancerConfiguration;
import org.mobicents.tools.sip.balancer.BalancerRunner;
import org.mobicents.tools.sip.balancer.BlackholeAppServer;
import org.mobicents.tools.sip.balancer.operation.Shootist;

//BlackholeAppServer is causing excesive CPU usage. Disable this test for now.
public class UdpForwardingPerformanceTest
{
	static final String inviteRequest = "INVITE sip:joe@company.com SIP/2.0\r\n"+
	"To: sip:joe@company.com\r\n"+
	"From: sip:caller@university.edu ;tag=1234\r\n"+
	"Call-ID: 0ha0isnda977644900765@10.0.0.1\r\n"+
	"CSeq: 9 INVITE\r\n"+
	"Via: SIP/2.0/UDP 135.180.130.133\r\n"+
	"Content-Type: application/sdp\r\n"+
	"\r\n"+
	"v=0\r\n"+
	"o=mhandley 29739 7272939 IN IP4 126.5.4.3\r\n" +
	"c=IN IP4 135.180.130.88\r\n" +
	"m=video 3227 RTP/AVP 31\r\n" +
	"m=audio 4921 RTP/AVP 12\r\n" +
	"a=rtpmap:31 LPC\r\n";

	static byte[] inviteRequestBytes = inviteRequest.getBytes();
	
	static final String ringing = 	"SIP/2.0 180 Ringing\n" + "To: <sip:LittleGuy@there.com>;tag=5432\n" +
	"Via: SIP/2.0/UDP 127.0.0.1:5065;branch=z9hG4bK-3530-488ff2840f609639903eff914df9870f202e2zsd,SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-3530-488ff2840f609639903eff914df9870f202e2,SIP/2.0/UDP 127.0.0.1:5033;branch=z9hG4bK-3530-488ff2840f609639903eff914df9870f\n"+
	"Record-Route: <sip:127.0.0.1:5065;transport=udp;lr>,<sip:127.0.0.1:5060;transport=udp;lr>\n"+
	"CSeq: 1 INVITE\n"+
	"Call-ID: 202e236d75a43c17b234a992873c3c74@127.0.0.1\n"+
	"From: <sip:BigGuy@here.com>;tag=12345\n"+
	"Content-Length: 0\n";
	
	static byte[] ringingBytes = ringing.getBytes();
	
	BalancerRunner balancer;
	int numNodes = 2;
	BlackholeAppServer server;
	Shootist shootist;
	
	static InetAddress localhost;
	static int callIdByteStart = -1;
	static {
		try {
			localhost = InetAddress.getByName("127.0.0.1");
			byte[] callid = "0ha0isn".getBytes();
			for (int q = 0; q < 1000; q++) {
				int found = -1;
				for (int w = 0; w < callid.length; w++) {
					if (callid[w] != inviteRequestBytes[q + w])
						break;
					found = w;
				}
				if (found > 0) {
					callIdByteStart = q;
					break;
				}
			}
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}
	private static long n = 0;
	private static void modCallId() {
		n++;
		inviteRequestBytes[callIdByteStart] = (byte) (n&0xff);
		inviteRequestBytes[callIdByteStart+1] = (byte) ((n>>8)&0xff);
		inviteRequestBytes[callIdByteStart+2] = (byte) ((n>>16)&0xff);
	}

	@Before
	public void setUp() throws Exception {

		shootist = new Shootist();
		balancer = new BalancerRunner();
		LoadBalancerConfiguration lbConfig = new LoadBalancerConfiguration();
		lbConfig.getSipConfiguration().getExternalLegConfiguration().setHost("127.0.0.1");
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setHost("127.0.0.1");
		lbConfig.getSipConfiguration().getInternalLegConfiguration().setUdpPort(5065);
		balancer.start(lbConfig);
		
		server = new BlackholeAppServer("blackhole", 18452, "127.0.0.1");
		server.start();
		Thread.sleep(5000);
		
	}
	
	//@Test
	public void testInvitePerformanceLong() {
		testMessagePerformance(1*30*1000, 100000, inviteRequestBytes);
	}
	
	//@Test
	public void testInvitePerformance10sec() {
		testMessagePerformance(10*1000, 100, inviteRequestBytes);
	}
	
	//@Test
	public void testInvitePerformanceDiffCallId10sec() {
		testDiffCallIdPerformance(10*1000, 100);
	}
	
	//@Test
	public void testRingingPerformance10sec() {
		testMessagePerformance(10*1000, 100, ringingBytes);
	}
	
	private void testMessagePerformance(int timespan, int maxLostPackets, byte[] bytes) {
		try {
			DatagramSocket socket = new DatagramSocket(33276, localhost);
			long sentPackets = 0;
			long startTime = System.currentTimeMillis();
			while(true) {
				boolean diffNotTooBig = sentPackets - server.numUnitsReceived<maxLostPackets;
				boolean thereIsStillTime = System.currentTimeMillis()-startTime<timespan;
				if(!thereIsStillTime) {
					break;
				}
				if(diffNotTooBig) {
					socket.send(new DatagramPacket(bytes,bytes.length,localhost, 5060));
					sentPackets++;
				} else {
					Thread.sleep(1);
				}
			}
			System.out.println("Packets sent in " + timespan + " ms are " + sentPackets + "(making " + server.numUnitsReceived/((double)(timespan)/1000.) + " initial requests per second)");
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void testDiffCallIdPerformance(int timespan, int maxLostPackets) {
		try {
			DatagramSocket socket = new DatagramSocket(33276, localhost);
			long sentPackets = 0;
			long startTime = System.currentTimeMillis();
			while(true) {
				boolean diffNotTooBig = sentPackets - server.numUnitsReceived<maxLostPackets;
				boolean thereIsStillTime = System.currentTimeMillis()-startTime<timespan;
				if(!thereIsStillTime) {
					break;
				}
				if(diffNotTooBig) {
					socket.send(new DatagramPacket(inviteRequestBytes,inviteRequestBytes.length,localhost, 5060));
					modCallId();
					sentPackets++;
				} else {
					Thread.sleep(1);
				}
			}
			System.out.println("Packets sent in " + timespan + " ms are " + sentPackets + "(making " + server.numUnitsReceived/((double)(timespan)/1000.) + " initial requests per second)");
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@After
	public void tearDown() throws Exception {
		server.stop();
		balancer.stop();
	}
	
	/*public static void main(String[] args) {
		try {
		UdpForwardingPerformanceTest test = new UdpForwardingPerformanceTest();
		test.setUp();
		test.testInvitePerformanceLong();
		test.tearDown();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}*/
}
