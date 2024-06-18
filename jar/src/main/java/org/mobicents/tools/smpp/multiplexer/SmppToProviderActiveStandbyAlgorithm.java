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
package org.mobicents.tools.smpp.multiplexer;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.mobicents.tools.heartbeat.api.Node;
import org.mobicents.tools.sip.balancer.KeySmpp;
import org.mobicents.tools.smpp.multiplexer.MClientConnectionImpl.ClientState;

import com.cloudhopper.smpp.pdu.Pdu;
/**
 * @author Konstantin Nosach (kostyantyn.nosach@telestax.com)
 */
public class SmppToProviderActiveStandbyAlgorithm  extends DefaultSmppAlgorithm
{

	@Override
	public void processSubmitToNode(ConcurrentHashMap<Long, MServerConnectionImpl> connectionsToNodes, Long serverSessionId, Pdu packet) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void processSubmitToProvider(ConcurrentHashMap<Long, MClientConnectionImpl> connectionsToProviders, Long sessionId, Pdu packet) {
		
		if(connectionsToProviders.get(0l)!=null&&connectionsToProviders.get(0l).getClientState()==ClientState.BOUND)
			connectionsToProviders.get(0l).sendSmppRequest(sessionId, packet);
		else
			connectionsToProviders.get(1l).sendSmppRequest(sessionId, packet);
		
	}

	@Override
	public void init() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void stop() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void configurationChanged() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Node processBindToProvider() {
		
			if(invocationContext.smppNodeMap.size() == 0) return null;
			if (invocationContext.activeNodeKey==null)
			{
				new ArrayList(invocationContext.smppNodeMap.values()).get(0);
				invocationContext.activeNodeKey = new KeySmpp((Node) new ArrayList(invocationContext.smppNodeMap.values()).get(0));
				return invocationContext.smppNodeMap.get(invocationContext.activeNodeKey);
			}
			else
				return invocationContext.smppNodeMap.get(invocationContext.activeNodeKey);
	}

}
