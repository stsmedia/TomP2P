/*
 * Copyright 2011 Thomas Bocek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.connection;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import net.tomp2p.message.TomP2PDecoderTCP;
import net.tomp2p.message.TomP2PDecoderUDP;
import net.tomp2p.message.TomP2PEncoderTCP;
import net.tomp2p.message.TomP2PEncoderUDP;
import net.tomp2p.p2p.P2PConfiguration;
import net.tomp2p.p2p.PeerListener;
import net.tomp2p.p2p.Scheduler;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerMap;

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictor;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the server connections of a node, i.e. the connections a node is
 * listening on.
 * 
 * @author Thomas Bocek
 * 
 */
public class ConnectionHandler
{
	final public static String THREAD_NAME = "Netty thread (non-blocking)/ ";
	static
	{
		ThreadRenamingRunnable.setThreadNameDeterminer(new ThreadNameDeterminer()
		{
			@Override
			public String determineThreadName(String currentThreadName, String proposedThreadName)
					throws Exception
			{
				return THREAD_NAME + currentThreadName;
				// to debug, use time to see when this thread was created:
				// return THREAD_NAME + currentThreadName +" / "+
				// System.nanoTime();
			}
		});
	}

	final private static Logger logger = LoggerFactory.getLogger(ConnectionHandler.class);
	// Stores the node information about this node
	final private ConnectionBean connectionBean;
	final private PeerBean peerBean;
	final private NATUtils natUtils;
	final public static int UDP_LIMIT = 1400;
	// Used to calculate the throughput
	final private static PerformanceFilter performanceFilter = new PerformanceFilter();
	final private MessageLogger messageLoggerFilter;
	final private List<ConnectionHandler> childConnections = new ArrayList<ConnectionHandler>();
	final private Timer timer;
	final private boolean master;

	final private ChannelFactory udpChannelFactory;
	final private ChannelFactory tcpServerChannelFactory;
	final private ChannelFactory tcpClientChannelFactory;

	/**
	 * 
	 * @param udpPort
	 * @param tcpPort
	 * @param id
	 * @param bindings
	 * @param p2pID
	 * @param configuration
	 * @param messageLogger
	 * @param keyPair
	 * @param peerMap
	 * @param listeners
	 * @param peerConfiguration
	 * @throws Exception
	 */
	public ConnectionHandler(int udpPort, int tcpPort, Number160 id, Bindings bindings, int p2pID,
			ConnectionConfigurationBean configuration, File messageLogger, KeyPair keyPair,
			PeerMap peerMap,
			List<PeerListener> listeners, P2PConfiguration peerConfiguration) throws Exception
	{
		this.timer = new HashedWheelTimer();
		this.udpChannelFactory = new NioDatagramChannelFactory(Executors.newCachedThreadPool());
		this.tcpServerChannelFactory = new NioServerSocketChannelFactory(
				Executors.newCachedThreadPool(),
				Executors.newCachedThreadPool());
		this.tcpClientChannelFactory = new NioClientSocketChannelFactory(
				Executors.newCachedThreadPool(),
				Executors.newCachedThreadPool());
		//
		final boolean listenAll = bindings.isListenAll();
		String status = DiscoverNetworks.discoverInterfaces(bindings);
		logger.info("Status of interface search: " + status);
		InetAddress outsideAddress = bindings.getExternalAddress();
		PeerAddress self;
		if (outsideAddress == null)
		{
			if (bindings.getAddresses0().size() == 0)
				throw new IOException(
						"Not listening to anything. Maybe your binding information is wrong.");
			outsideAddress = bindings.getAddresses0().get(0);
			self = new PeerAddress(id, outsideAddress, tcpPort, udpPort,
					peerConfiguration.isBehindFirewall(), peerConfiguration.isBehindFirewall());
		}
		else
		{
			self = new PeerAddress(id, outsideAddress, bindings.getOutsideTCPPort(),
					bindings.getOutsideUDPPort(),
					peerConfiguration.isBehindFirewall(), peerConfiguration.isBehindFirewall());
		}
		peerBean = new PeerBean(keyPair);
		peerBean.setServerPeerAddress(self);
		peerBean.setPeerMap(peerMap);
		logger.info("Visible address to other peers: " + self);
		messageLoggerFilter = messageLogger == null ? null : new MessageLogger(messageLogger);
		ConnectionReservation reservation = new ConnectionReservation(tcpClientChannelFactory,
				udpChannelFactory, configuration, messageLoggerFilter, peerMap.getStatistics());
		ChannelGroup channelGroup = new DefaultChannelGroup("TomP2P ConnectionHandler");
		DispatcherReply dispatcherRequest = new DispatcherReply(p2pID, peerBean,
				configuration.getIdleUDPMillis(),
				configuration.getIdleTCPMillis(), channelGroup, peerMap, listeners);
		// Dispatcher setup stop
		Scheduler scheduledPeer = new Scheduler();
		Sender sender = new Sender(configuration, timer);
		connectionBean = new ConnectionBean(p2pID, dispatcherRequest, sender, channelGroup,
				scheduledPeer, reservation, configuration);
		if (listenAll)
		{
			logger.info("Listening for broadcasts on port udp: " + udpPort + " and tcp:" + tcpPort);
			if (!startupTCP(new InetSocketAddress(tcpPort), dispatcherRequest,
					configuration.getMaxMessageSize())
					|| !startupUDP(new InetSocketAddress(udpPort), dispatcherRequest))
				throw new IOException("cannot bind TCP or UDP");
		}
		else
		{
			for (InetAddress addr : bindings.getAddresses())
			{
				logger.info("Listening on address: " + addr + " on port udp: " + udpPort
						+ " and tcp:" + tcpPort);
				if (!startupTCP(new InetSocketAddress(addr, tcpPort), dispatcherRequest,
						configuration.getMaxMessageSize())
						|| !startupUDP(new InetSocketAddress(addr, udpPort), dispatcherRequest))
					throw new IOException("cannot bind TCP or UDP");
			}
		}
		natUtils = new NATUtils();
		master = true;
	}

	/**
	 * Attaches a peer to an existing connection and use existing information.
	 * The following objects are never shared: id, keyPair, peerMap.
	 * 
	 * @param parent The parent handler
	 * @param id The id of the child peer
	 * @param keyPair The keypair of the child peer
	 * @param peerMap The peer map of the child peer
	 */
	public ConnectionHandler(ConnectionHandler parent, Number160 id, KeyPair keyPair,
			PeerMap peerMap)
	{
		parent.childConnections.add(this);
		this.connectionBean = parent.connectionBean;
		PeerAddress self = parent.getPeerBean().getServerPeerAddress().changePeerId(id);
		this.peerBean = new PeerBean(keyPair);
		this.peerBean.setServerPeerAddress(self);
		this.peerBean.setPeerMap(peerMap);
		this.messageLoggerFilter = parent.messageLoggerFilter;
		this.udpChannelFactory = parent.udpChannelFactory;
		this.tcpServerChannelFactory = parent.tcpServerChannelFactory;
		this.tcpClientChannelFactory = parent.tcpClientChannelFactory;
		this.timer = parent.timer;
		this.natUtils = parent.natUtils;
		this.master = false;
	}

	/**
	 * @return The shared connection configuration
	 */
	public ConnectionBean getConnectionBean()
	{
		return connectionBean;
	}

	/**
	 * @return The non-shared peer configuration
	 */
	public PeerBean getPeerBean()
	{
		return peerBean;
	}

	/**
	 * @return The NAT utils to setup port forwarding using NAT-PMP and UPNP.
	 */
	public NATUtils getNATUtils()
	{
		return natUtils;
	}

	/**
	 * Creates UDP channels and listens on them
	 * 
	 * @param listenAddressesUDP The address to listen to
	 * @param dispatcher The dispatcher that handles incoming messages
	 * @return True if the channel was bound.
	 */
	public boolean startupUDP(InetSocketAddress listenAddressesUDP, final DispatcherReply dispatcher)
	{
		ConnectionlessBootstrap bootstrap = new ConnectionlessBootstrap(udpChannelFactory);
		bootstrap.setPipelineFactory(new ChannelPipelineFactory()
		{
			@Override
			public ChannelPipeline getPipeline() throws Exception
			{
				ChannelPipeline pipe = Channels.pipeline();
				pipe.addLast("encoder", new TomP2PEncoderUDP());
				pipe.addLast("decoder", new TomP2PDecoderUDP());
				if (messageLoggerFilter != null)
				{
					pipe.addLast("loggerUpstream", messageLoggerFilter);
				}
				pipe.addLast("performance", performanceFilter);
				pipe.addLast("handler", dispatcher);
				return pipe;
			}
		});
		bootstrap.setOption("broadcast", "false");
		bootstrap.setOption("receiveBufferSizePredictor", new FixedReceiveBufferSizePredictor(
				ConnectionHandler.UDP_LIMIT));
		Channel channel = bootstrap.bind(listenAddressesUDP);
		logger.info("Listening on UDP socket: " + listenAddressesUDP);
		connectionBean.getChannelGroup().add(channel);
		return channel.isBound();
	}

	/**
	 * Creates TCP channels and listens on them
	 * 
	 * @param listenAddressesTCP the addresses which we will listen on
	 * @param dispatcher The dispatcher that handles incoming messages
	 * @param maxMessageSize The maximum message size that is tolerated
	 * @return True if the channel was bound.
	 */
	public boolean startupTCP(InetSocketAddress listenAddressesTCP,
			final DispatcherReply dispatcher, int maxMessageSize)
	{
		ServerBootstrap bootstrap = new ServerBootstrap(tcpServerChannelFactory);
		bootstrap.setPipelineFactory(new ChannelPipelineFactory()
		{
			@Override
			public ChannelPipeline getPipeline() throws Exception
			{
				ChannelPipeline pipe = Channels.pipeline();
				// TODO: enable a 2min timeout
				// ReplyTimeoutHandler timeoutHandler = new
				// ReplyTimeoutHandler(timer,
				// connectionBean.getConfiguration().getIdleTCPMillis(),
				// getPeerBean().getServerPeerAddress());
				// pipe.addLast("timeout", timeoutHandler);
				pipe.addLast("streamer", new ChunkedWriteHandler());
				pipe.addLast("encoder", new TomP2PEncoderTCP());
				pipe.addLast("decoder", new TomP2PDecoderTCP());
				if (messageLoggerFilter != null)
					pipe.addLast("loggerUpstream", messageLoggerFilter);
				pipe.addLast("performance", performanceFilter);
				pipe.addLast("handler", dispatcher);
				return pipe;
			}
		});
		//as suggested by http://stackoverflow.com/questions/8442166/how-to-allow-more-concurrent-client-connections-with-netty
		bootstrap.setOption("backlog", 1000);
		Channel channel = bootstrap.bind(listenAddressesTCP);
		connectionBean.getChannelGroup().add(channel);
		logger.info("Listening on TCP socket: " + listenAddressesTCP);
		return channel.isBound();
	}

	/**
	 * Writes a custom message to the logger filter. Only used if the logger
	 * filter has been setup properly.
	 * 
	 * @param customMessage The custom message to print.
	 */
	public void customLoggerMessage(String customMessage)
	{
		if (messageLoggerFilter != null)
		{
			messageLoggerFilter.customMessage(customMessage);
		}
		else
		{
			logger.error("cannot write to log, as no file was provided");
		}
	}

	/**
	 * Shuts down the dispatcher and frees resources. This closes all channels.
	 */
	public void shutdown()
	{
		if (master)
		{
			if (logger.isDebugEnabled())
			{
				logger.debug("shutdown in progress..." + System.nanoTime());
			}
		}
		// deregister in dispatcher
		connectionBean.getDispatcherRequest().removeIoHandler(
				getPeerBean().getServerPeerAddress().getID());
		// shutdown all children
		for (ConnectionHandler handler : childConnections)
		{
			handler.shutdown();
		}
		if (master)
		{
			natUtils.shutdown();
			timer.stop();
			// channelChache.shutdown();
			if (messageLoggerFilter != null)
				messageLoggerFilter.shutdown();
			// close server first, then all connected clients. This is only done
			// by the master, other groups are
			// empty
			connectionBean.getScheduler().shutdownAndWait();
			connectionBean.getReservation().shutdown();
			connectionBean.getChannelGroup().close().awaitUninterruptibly();
			// release resources
			udpChannelFactory.releaseExternalResources();
			tcpServerChannelFactory.releaseExternalResources();
			tcpClientChannelFactory.releaseExternalResources();
			if (logger.isDebugEnabled())
			{
				logger.debug("shutdown complete");
			}
		}
	}

	/**
	 * @return Returns true if this handler is attached to listen to an
	 *         interface.
	 */
	public boolean isListening()
	{
		return !getConnectionBean().getChannelGroup().isEmpty();
	}
}
