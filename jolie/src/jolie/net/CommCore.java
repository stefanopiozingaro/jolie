/*******************************************************************************
 *   Copyright (C) 2006-2017 by Fabrizio Montesi <famontesi@gmail.com>         *
 *   Copyright (C) 2017 by Martin Møller Andersen <maan511@student.sdu.dk>     *
 *   Copyright (C) 2017 by Saverio Giallorenzo <saverio.giallorenzo@gmail.com> *
 *   Copyright (C) 2018 by Stefano Pio Zingaro <stefanopio.zingaro@unibo.it>   *
 *                                                                             *
 *   This program is free software; you can redistribute it and/or modify      *
 *   it under the terms of the GNU Library General Public License as           *
 *   published by the Free Software Foundation; either version 2 of the        *
 *   License, or (at your option) any later version.                           *
 *                                                                             *
 *   This program is distributed in the hope that it will be useful,           *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of            *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             *
 *   GNU General Public License for more details.                              *
 *                                                                             *
 *   You should have received a copy of the GNU Library General Public         *
 *   License along with this program; if not, write to the                     *
 *   Free Software Foundation, Inc.,                                           *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.                 *
 *                                                                             *
 *   For details about the authors of this software, see the AUTHORS file.     *
 *******************************************************************************/
package jolie.net;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jolie.ExecutionThread;
import jolie.Interpreter;
import jolie.JolieThreadPoolExecutor;
import jolie.NativeJolieThread;
import jolie.lang.Constants;
import jolie.net.ext.CommChannelFactory;
import jolie.net.ext.CommListenerFactory;
import jolie.net.ext.CommProtocolFactory;
import jolie.net.ext.PubSubCommProtocolFactory;
import jolie.net.ports.InputPort;
import jolie.net.ports.OutputPort;
import jolie.net.protocols.CommProtocol;
import jolie.process.Process;
import jolie.runtime.FaultException;
import jolie.runtime.InputOperation;
import jolie.runtime.InvalidIdException;
import jolie.runtime.OneWayOperation;
import jolie.runtime.TimeoutHandler;
import jolie.runtime.Value;
import jolie.runtime.VariablePath;
import jolie.runtime.correlation.CorrelationError;
import jolie.runtime.typing.TypeCheckingException;

/**
 * Handles the communications mechanisms for an Interpreter instance.
 *
 * Each CommCore is related to an Interpreter, and each Interpreter owns one and only CommCore instance.
 *
 * @author Fabrizio Montesi
 */
public class CommCore
{

	private final Map< String, CommListener> listenersMap = new HashMap<>();
	private final static int CHANNEL_HANDLER_TIMEOUT = 5;
	private final ThreadGroup threadGroup;
	private final ChannelPool channelPool = new ChannelPool();
	private final MessagePool messagePool = new MessagePool();
	private final ThreadRegistry requestThreadRegistry = new ThreadRegistry();
	private final ThreadRegistry responseThreadRegistry = new ThreadRegistry();

	private static final Logger logger = Logger.getLogger( "JOLIE" );

	private final int connectionsLimit;
	// private final int connectionCacheSize;
	private final Interpreter interpreter;

	private final ReadWriteLock channelHandlersLock = new ReentrantReadWriteLock( true );

	// Location URI -> Protocol name -> Persistent CommChannel object
	private final Map< URI, Map< String, CommChannel>> persistentChannels = new HashMap<>();

	private void removePersistentChannel( URI location, String protocol, Map< String, CommChannel> protocolChannels )
	{
		protocolChannels.remove( protocol );
		if ( protocolChannels.isEmpty() ) {
			persistentChannels.remove( location );
		}
	}

	private void removePersistentChannel( URI location, String protocol, CommChannel channel )
	{
		if ( persistentChannels.containsKey( location ) ) {
			if ( persistentChannels.get( location ).get( protocol ) == channel ) {
				removePersistentChannel( location, protocol, persistentChannels.get( location ) );
			}
		}
	}

	public CommChannel getPersistentChannel( URI location, String protocol )
	{
		CommChannel ret = null;
		synchronized( persistentChannels ) {
			Map< String, CommChannel> protocolChannels = persistentChannels.get( location );
			if ( protocolChannels != null ) {
				ret = protocolChannels.get( protocol );
				if ( ret != null ) {
					if ( ret.lock.tryLock() ) {
						if ( ret.isOpen() ) {
							/*
							 * We are going to return this channel, but first
							 * check if it supports concurrent use.
							 * If not, then others should not access this until
							 * the caller is finished using it.
							 */
							//if ( ret.isThreadSafe() == false ) {
							removePersistentChannel( location, protocol, protocolChannels );
							//} else {
							// If we return a channel, make sure it will not timeout!
							ret.setTimeoutHandler( null );
							//if ( ret.timeoutHandler() != null ) {
							//interpreter.removeTimeoutHandler( ret.timeoutHandler() );
							// ret.setTimeoutHandler( null );
							//}
							//}
							ret.lock.unlock();
						} else { // Channel is closed
							removePersistentChannel( location, protocol, protocolChannels );
							ret.lock.unlock();
							ret = null;
						}
					} else { // Channel is busy
						removePersistentChannel( location, protocol, protocolChannels );
						ret = null;
					}
				}
			}
		}

		return ret;
	}

	private void setTimeoutHandler( final CommChannel channel, final URI location, final String protocol )
	{
		/*if ( channel.timeoutHandler() != null ) {
			interpreter.removeTimeoutHandler( channel.timeoutHandler() );
		}*/

		final TimeoutHandler handler = new TimeoutHandler( interpreter.persistentConnectionTimeout() )
		{
			@Override
			public void onTimeout()
			{
				try {
					synchronized( persistentChannels ) {
						if ( channel.timeoutHandler() == this ) {
							removePersistentChannel( location, protocol, channel );
							channel.close();
							channel.setTimeoutHandler( null );
						}
					}
				} catch( IOException e ) {
					interpreter.logSevere( e );
				}
			}
		};
		channel.setTimeoutHandler( handler );
		interpreter.addTimeoutHandler( handler );
	}

	public void putPersistentChannel( URI location, String protocol, final CommChannel channel )
	{
		synchronized( persistentChannels ) {
			Map< String, CommChannel> protocolChannels = persistentChannels.get( location );
			if ( protocolChannels == null ) {
				protocolChannels = new HashMap<>();
				persistentChannels.put( location, protocolChannels );
			}
			// Set the timeout
			setTimeoutHandler( channel, location, protocol );
			// Put the protocol in the cache (may overwrite another one)
			protocolChannels.put( protocol, channel );
			/*if ( protocolChannels.size() <= connectionCacheSize && protocolChannels.containsKey( protocol ) == false ) {
				// Set the timeout
				setTimeoutHandler( channel );
				// Put the protocol in the cache
				protocolChannels.put( protocol, channel );
			} else {
				try {
					if ( protocolChannels.get( protocol ) != channel ) {
						channel.close();
					} else {
						setTimeoutHandler( channel );
					}
				} catch( IOException e ) {
					interpreter.logWarning( e );
				}
			}*/
		}
	}

	/**
	 * Returns the Interpreter instance this CommCore refers to.
	 *
	 * @return the Interpreter instance this CommCore refers to
	 */
	public Interpreter interpreter()
	{
		return interpreter;
	}

	/**
	 * Constructor.
	 *
	 * @param interpreter the Interpreter to refer to for this CommCore operations
	 * @param connectionsLimit if more than zero, specifies an upper bound to the connections handled in parallel.
	 * @throws java.io.IOException
	 */
	public CommCore( Interpreter interpreter, int connectionsLimit /*, int connectionsCacheSize */ )
		throws IOException
	{
		this.interpreter = interpreter;
		this.localListener = LocalListener.create( interpreter );
		this.connectionsLimit = connectionsLimit;
		// this.connectionCacheSize = connectionsCacheSize;
		this.threadGroup = new ThreadGroup( "CommCore-" + interpreter.hashCode() );
		/* if ( connectionsLimit > 0 ) {
			executorService = Executors.newFixedThreadPool( connectionsLimit, new CommThreadFactory() );
		} else {
			executorService = Executors.newCachedThreadPool( new CommThreadFactory() );
		}
		 */
		executorService = new JolieThreadPoolExecutor( new CommThreadFactory() );

		selectorThreads = new SelectorThread[ Runtime.getRuntime().availableProcessors() ];
		for( int i = 0; i < selectorThreads.length; i++ ) {
			selectorThreads[ i ] = new SelectorThread( interpreter );
		}

		EventLoopGroup bossGroup = new NioEventLoopGroup( 4, new ExecutionContextThreadFactory() );
		EventLoopGroup workerGroup = new NioEventLoopGroup( 4, new ExecutionContextThreadFactory() );
		//TODO make socket an extension, too?
		CommListenerFactory listenerFactory = new NioSocketListenerFactory( this, bossGroup, workerGroup );
		listenerFactories.put( "socket", listenerFactory );
		CommChannelFactory channelFactory = new NioSocketCommChannelFactory( this, workerGroup );
		channelFactories.put( "socket", channelFactory );
	}

	public ExecutionContextThreadFactory getNewExecutionContextThreadFactory()
	{
		return new ExecutionContextThreadFactory();
	}

	public class ExecutionContextThread extends Thread
	{

		private Interpreter interpreter;
		private ExecutionThread executionThread = null;

		private ExecutionContextThread( Runnable r, Interpreter interpreter )
		{
			super( r );
			this.interpreter = interpreter;
		}

		public void executionThread( ExecutionThread ethread )
		{
			executionThread = ethread;
		}

		public ExecutionThread executionThread()
		{
			return executionThread;
		}

		public Interpreter interpreter()
		{
			return interpreter;
		}

	}

	public class ExecutionContextThreadFactory implements ThreadFactory
	{

		@Override
		public Thread newThread( Runnable r )
		{
			return new ExecutionContextThread( r, interpreter() );
		}

	}

	public void receiveResponse( CommMessage m )
	{
		messagePool.receiveResponse( m );
	}

	public CommMessage recvResponseFor( CommChannel c, CommMessage message ) throws IOException
	{
		if ( c == null /* TODO: temporary check for local channels, fix this */ ) {
			return messagePool.recvResponseFor( message );
		} else {
			return c.recvResponseFor( message );
		}
	}

	public <C> ExecutionThread getRequestExecutionThread( C k )
	{
		return getExecutionThread( k, requestThreadRegistry );
	}

	public <C> void removeRequestExecutionThread( C k )
	{
		removeExecutionThread( k, requestThreadRegistry );
	}

	public <C> ExecutionThread getResponseExecutionThread( C k )
	{
		return getExecutionThread( k, responseThreadRegistry );
	}

	public <C> void removeResponseExecutionThread( C k )
	{
		removeExecutionThread( k, responseThreadRegistry );
	}

	private <C> ExecutionThread getExecutionThread( C k, ThreadRegistry threadRegistry )
	{
		if ( k == null ) {
			throw new UnsupportedOperationException( "Null object passed to look for ExecutionThread" );
		} else if ( k instanceof CommChannel ) {
			return threadRegistry.getThread( (CommChannel) k );
		} else if ( k instanceof Long ) {
			return threadRegistry.getThread( (Long) k );
		} else {
			throw new UnsupportedOperationException( "Wrong Class " + k.getClass().toString() + " passed to look for ExecutionThread" );
		}
	}

	private <C> void removeExecutionThread( C k, ThreadRegistry threadRegistry )
	{
		if ( k == null ) {
			throw new UnsupportedOperationException( "Null object passed to look for ExecutionThread" );
		} else if ( k instanceof CommChannel ) {
			threadRegistry.removeThread( (CommChannel) k );
		} else if ( k instanceof Long ) {
			threadRegistry.removeThread( (Long) k );
		} else {
			throw new UnsupportedOperationException( "Wrong Class " + k.getClass().toString() + " passed to look for ExecutionThread" );
		}
	}

	public CommMessage retrieveSynchronousRequest( CommChannel c )
	{
		return messagePool.retrieveSynchronousRequest( c );
	}

	public String retrieveAsynchronousRequest( long id )
	{
		return messagePool.retrieveAsynchronousRequest( id );
	}

	public void sendCommMessage( CommMessage message, URI location, OutputPort out, boolean threadSafe )
		throws IOException, URISyntaxException
	{
		CommChannel c = channelPool.getChannel( threadSafe, location, out );
		// we always add the thread associated to the message (this is consumed when encoding to message to be sent)
		// THIS IS A REQUEST
		requestThreadRegistry.addThread( message, ExecutionThread.currentThread() );
		// we also add the thread associated to the channel (this is consumed when decoding the response message) 
		if ( !threadSafe ) {
			requestThreadRegistry.addThread( c, ExecutionThread.currentThread() );
			messagePool.registerForSynchronousResponse( c, message );
		} else {
			messagePool.registerForAsynchronousResponse( message.id(), message.operationName() );
		}
		c.send( message );
		if ( threadSafe ) {
			releaseChannel( c );
		}
	}

	public void registerResponseThread( CommChannel c, ExecutionThread t )
	{
		responseThreadRegistry.addThread( c, t );
	}

	public void releaseChannel( CommChannel c ) throws IOException
	{
		if ( c.parentOutputPort() != null ) {
			String protocol = "none";
			channelPool.releaseChannel( c.isThreadSafe(), c.getLocation(), c.parentOutputPort(), c );
		} else {
			throw new IOException( "Cannot release a channel without an OutputPort" );
		}
	}

	public CommChannel createCommChannel( URI uri, OutputPort port )
		throws IOException, URISyntaxException
	{
		String medium = uri.getScheme();
		CommProtocolFactory fetchedFactory = null;
		String name = null;
		try {
			name = port.getProtocol().name();
		} catch( IOException ex ) {
			// we do nothing, simply the port has no specified protocol, which will be handled by createEndToEndCommChannel
		}
		if ( name != null ) {
			fetchedFactory = getCommProtocolFactory( name );
		}
		if ( fetchedFactory != null && fetchedFactory instanceof PubSubCommProtocolFactory ) {
			return createPubSubCommChannel( uri, port );
		} else {
			return createEndToEndCommChannel( uri, port );
		}
	}

	public CommChannel createEndToEndCommChannel( URI uri, OutputPort port ) throws IOException
	{
		String medium = uri.getScheme();
		CommChannelFactory factory = getCommChannelFactory( medium );
		if ( factory == null ) {
			throw new UnsupportedCommMediumException( medium );
		}
		return factory.createChannel( uri, port );
	}

	public CommChannel createInputCommChannel( URI uri, InputPort port ) throws IOException
	{
		String medium = uri.getScheme();
		CommChannelFactory channelFactory = getCommChannelFactory( medium );
		if ( channelFactory == null ) {
			throw new UnsupportedCommMediumException( medium );
		}
		String protocolName = port.protocolConfigurationPath().getValue().strValue();
		CommProtocolFactory protocolFactory
			= interpreter.commCore().getCommProtocolFactory( protocolName );
		if ( protocolFactory == null ) {
			throw new UnsupportedCommProtocolException( protocolName );
		}
		CommProtocol protocol = protocolFactory.createInputProtocol( port.protocolConfigurationPath(), uri );
		return channelFactory.createInputChannel( uri, port, protocol );
	}

	public CommChannel createPubSubCommChannel( URI uri, OutputPort port )
		throws IOException
	{
		CommChannelFactory factory = getCommChannelFactory( "pubsubchannel" );
		if ( factory == null ) {
			throw new UnsupportedCommMediumException( "pubsubchannel" );
		}

		return factory.createChannel( uri, port );
	}

	private final Map< String, CommProtocolFactory> protocolFactories = new HashMap<>();

	public CommProtocolFactory getCommProtocolFactory( String name )
		throws IOException
	{
		CommProtocolFactory factory = protocolFactories.get( name );
		if ( factory == null ) {
			factory = interpreter.getClassLoader().createCommProtocolFactory( name, this );
			if ( factory != null ) {
				protocolFactories.put( name, factory );
			}
		}
		return factory;
	}

	public Logger logger()
	{
		return logger;
	}

	/**
	 * Returns the connectionsLimit of this CommCore.
	 *
	 * @return the connectionsLimit of this CommCore
	 */
	public int connectionsLimit()
	{
		return connectionsLimit;
	}

	public ThreadGroup threadGroup()
	{
		return threadGroup;
	}

	private final Collection< Process> protocolConfigurations = new LinkedList<>();

	public Collection< Process> protocolConfigurations()
	{
		return protocolConfigurations;
	}

	public CommListener getListenerByInputPortName( String serviceName )
	{
		return listenersMap.get( serviceName );
	}

	private final Map< String, CommChannelFactory> channelFactories = new HashMap<>();

	private CommChannelFactory getCommChannelFactory( String name )
		throws IOException
	{
		CommChannelFactory factory = channelFactories.get( name );
		if ( factory == null ) {
			factory = interpreter.getClassLoader().createCommChannelFactory( name, this );
			if ( factory != null ) {
				channelFactories.put( name, factory );
			}
		}
		return factory;
	}

	public CommProtocol createOutputCommProtocol( String protocolId, VariablePath configurationPath, URI uri )
		throws IOException
	{
		CommProtocolFactory factory = getCommProtocolFactory( protocolId );
		if ( factory == null ) {
			throw new UnsupportedCommProtocolException( protocolId );
		}

		return factory.createOutputProtocol( configurationPath, uri );
	}

	public CommProtocol createInputCommProtocol( String protocolId, VariablePath configurationPath, URI uri )
		throws IOException
	{
		CommProtocolFactory factory = getCommProtocolFactory( protocolId );
		if ( factory == null ) {
			throw new UnsupportedCommProtocolException( protocolId );
		}

		return factory.createInputProtocol( configurationPath, uri );
	}

	private final Map< String, CommListenerFactory> listenerFactories = new HashMap<>();

	private final LocalListener localListener;

	public LocalCommChannel getLocalCommChannel()
	{
		return new LocalCommChannel( interpreter, localListener );
	}

	public LocalCommChannel getLocalCommChannel( CommListener listener )
	{
		return new LocalCommChannel( interpreter, listener );
	}

	public CommListenerFactory getCommListenerFactory( String name )
		throws IOException
	{
		CommListenerFactory factory = listenerFactories.get( name );
		if ( factory == null ) {
			factory = interpreter.getClassLoader().createCommListenerFactory( name, this );
			if ( factory != null ) {
				listenerFactories.put( name, factory );
			}
		}
		return factory;
	}

	public LocalListener localListener()
	{
		return localListener;
	}

	public void addLocalInputPort( InputPort inputPort )
		throws IOException
	{
		localListener.mergeInterface( inputPort.getInterface() );
		localListener.addAggregations( inputPort.aggregationMap() );
		localListener.addRedirections( inputPort.redirectionMap() );
		listenersMap.put( inputPort.name(), localListener );
	}

	/**
	 * Adds an input port to this <code>CommCore</code>. This method is not thread-safe.
	 *
	 * @param inputPort the {@link InputPort} to add
	 * @param protocolFactory the <code>CommProtocolFactory</code> to use for the input port
	 * @param protocolConfigurationProcess the protocol configuration process to execute for configuring the created protocols
	 * @throws java.io.IOException in case of some underlying implementation error
	 * @see URI
	 * @see CommProtocolFactory
	 */
	public void addInputPort(
		InputPort inputPort,
		CommProtocolFactory protocolFactory,
		Process protocolConfigurationProcess
	)
		throws IOException
	{
		protocolConfigurations.add( protocolConfigurationProcess );

		String medium = inputPort.location().getScheme();
		if ( protocolFactory instanceof PubSubCommProtocolFactory ) {
			medium = PubSubCommProtocolFactory.getMedium();
		}
		CommListenerFactory factory = getCommListenerFactory( medium );
		if ( factory == null ) {
			throw new UnsupportedCommMediumException( medium );
		}

		CommListener listener = factory.createListener(
			interpreter,
			protocolFactory,
			inputPort
		);
		listenersMap.put( inputPort.name(), listener );
	}

	private final ExecutorService executorService;

	private final static class CommThreadFactory implements ThreadFactory
	{

		@Override
		public Thread newThread( Runnable r )
		{
			return new CommChannelHandler( r );
		}
	}

	private final static Pattern pathSplitPattern = Pattern.compile( "/" );

	private class CommChannelHandlerRunnable implements Runnable
	{

		private final CommChannel channel;
		private final InputPort port;

		public CommChannelHandlerRunnable( CommChannel channel, InputPort port )
		{
			this.channel = channel;
			this.port = port;
		}

		private void forwardResponse( CommMessage message )
			throws IOException
		{
			message = new CommMessage(
				channel.redirectionMessageId(),
				message.operationName(),
				message.resourcePath(),
				message.value(),
				message.fault()
			);
			try {
				try {
					channel.redirectionChannel().send( message );
				} finally {
					try {
						if ( channel.redirectionChannel().toBeClosed() ) {
							channel.redirectionChannel().close();
						} else {
							channel.redirectionChannel().disposeForInput();
						}
					} finally {
						channel.setRedirectionChannel( null );
					}
				}
			} finally {
				channel.closeImpl();
			}
		}

		private void handleRedirectionInput( CommMessage message, String[] ss )
			throws IOException, URISyntaxException
		{
			// Redirection
			String rPath;
			if ( ss.length <= 2 ) {
				rPath = "/";
			} else {
				StringBuilder builder = new StringBuilder();
				for( int i = 2; i < ss.length; i++ ) {
					builder.append( '/' );
					builder.append( ss[ i ] );
				}
				rPath = builder.toString();
			}
			OutputPort oPort = port.redirectionMap().get( ss[ 1 ] );
			if ( oPort == null ) {
				String error = "Discarded a message for resource " + ss[ 1 ]
					+ ", not specified in the appropriate redirection table.";
				interpreter.logWarning( error );
				throw new IOException( error );
			}
			try {
				CommChannel oChannel = oPort.getNewCommChannel();
				CommMessage rMessage
					= new CommMessage(
						message.id(),
						message.operationName(),
						rPath,
						message.value(),
						message.fault()
					);
				oChannel.setRedirectionChannel( channel );
				oChannel.setRedirectionMessageId( rMessage.id() );
				oChannel.send( rMessage );
				oChannel.setToBeClosed( false );
				oChannel.disposeForInput();
			} catch( IOException e ) {
				channel.send( CommMessage.createFaultResponse( message, new FaultException( Constants.IO_EXCEPTION_FAULT_NAME, e ) ) );
				channel.disposeForInput();
				throw e;
			}
		}

		private void handleAggregatedInput( CommMessage message, AggregatedOperation operation )
			throws IOException, URISyntaxException
		{
			operation.runAggregationBehaviour( message, channel );
		}

		private void handleDirectMessage( CommMessage message )
			throws IOException
		{
			try {
				InputOperation operation
					= interpreter.getInputOperation( message.operationName() );
				try {
					operation.requestType().check( message.value() );
					interpreter.correlationEngine().onMessageReceive( message, channel );
					if ( operation instanceof OneWayOperation ) {
						// We need to send the acknowledgement
						channel.send( CommMessage.createEmptyResponse( message ) );
						//channel.release();
					}
				} catch( TypeCheckingException e ) {
					interpreter.logWarning( "Received message TypeMismatch (input operation " + operation.id() + "): " + e.getMessage() );
					try {
						channel.send( CommMessage.createFaultResponse( message, new FaultException( jolie.lang.Constants.TYPE_MISMATCH_FAULT_NAME, e.getMessage() ) ) );
					} catch( IOException ioe ) {
						Interpreter.getInstance().logSevere( ioe );
					}
				} catch( CorrelationError e ) {
					interpreter.logWarning( "Received a non correlating message for operation " + message.operationName() + ". Sending CorrelationError to the caller." );
					channel.send( CommMessage.createFaultResponse( message, new FaultException( "CorrelationError", "The message you sent can not be correlated with any session and can not be used to start a new session." ) ) );
				}
			} catch( InvalidIdException e ) {
				interpreter.logWarning( "Received a message for undefined operation " + message.operationName() + ". Sending IOException to the caller." );
				channel.send( CommMessage.createFaultResponse( message, new FaultException( "IOException", "Invalid operation: " + message.operationName() ) ) );
			} finally {
				channel.disposeForInput();
			}
		}

		private void handleMessage( CommMessage message )
			throws IOException
		{
			try {
				String[] ss = pathSplitPattern.split( message.resourcePath() );
				if ( ss.length > 1 ) {
					handleRedirectionInput( message, ss );
				} else if ( port.canHandleInputOperationDirectly( message.operationName() ) ) {
					handleDirectMessage( message );
				} else {
					AggregatedOperation operation = port.getAggregatedOperation( message.operationName() );
					if ( operation == null ) {
						interpreter.logWarning(
							"Received a message for operation " + message.operationName()
							+ ", not specified in the input port at the receiving service. Sending IOException to the caller."
						);
						try {
							channel.send( CommMessage.createFaultResponse( message, new FaultException( "IOException", "Invalid operation: " + message.operationName() ) ) );
						} finally {
							channel.disposeForInput();
						}
					} else {
						handleAggregatedInput( message, operation );
					}
				}
			} catch( URISyntaxException e ) {
				interpreter.logSevere( e );
			}
		}

		@Override
		public void run()
		{
			final CommChannelHandler thread = CommChannelHandler.currentThread();
			thread.setExecutionThread( interpreter().initThread() );
			channel.lock.lock();
			channelHandlersLock.readLock().lock();
			try {
				if ( channel.redirectionChannel() == null ) {
					assert (port != null);
					final CommMessage message = channel.recv();
					if ( message != null ) {
						handleMessage( message );
					} else {
						channel.disposeForInput();
					}
				} else {
					channel.lock.unlock();
					CommMessage response = null;
					try {
						response = channel.recvResponseFor( new CommMessage( channel.redirectionMessageId(), "", "/", Value.UNDEFINED_VALUE, null ) );
					} finally {
						if ( response == null ) {
							response = new CommMessage( channel.redirectionMessageId(), "", "/", Value.UNDEFINED_VALUE, new FaultException( "IOException", "Internal server error" ) );
						}
						forwardResponse( response );
					}
				}
			} catch( ChannelClosingException e ) {
				interpreter.logFine( e );
			} catch( IOException e ) {
				interpreter.logSevere( e );
				try {
					channel.closeImpl();
				} catch( IOException e2 ) {
					interpreter.logSevere( e2 );
				}
			} finally {
				channelHandlersLock.readLock().unlock();
				if ( channel.lock.isHeldByCurrentThread() ) {
					channel.lock.unlock();
				}
				thread.setExecutionThread( null );
			}
		}
	}

	/**
	 * Schedules the receiving of a message on this <code>CommCore</code> instance.
	 *
	 * @param channel the <code>CommChannel</code> to use for receiving the message
	 * @param port the <code>Port</code> responsible for the message receiving
	 */
	public void scheduleReceive( CommChannel channel, InputPort port )
	{
		executorService.execute( new CommChannelHandlerRunnable( channel, port ) );
	}

	/**
	 * Runs an asynchronous task in this CommCore internal thread pool.
	 *
	 * @param r the Runnable object to execute
	 */
	public void execute( Runnable r )
	{
		executorService.execute( r );
	}

	protected void startCommChannelHandler( Runnable r )
	{
		executorService.execute( r );
	}

	/**
	 * Initializes the communication core, starting its communication listeners. This method is asynchronous. When it returns, every
	 * communication listener has been issued to start, but they are not guaranteed to be ready to receive messages. This method throws an
	 * exception if some listener cannot be issued to start; other errors will be logged by the listener through the interpreter logger.
	 *
	 * @throws IOException in case of some underlying <code>CommListener</code> initialization error
	 * @see CommListener
	 */
	public void init()
		throws IOException
	{
		active = true;
		for( SelectorThread t : selectorThreads ) {
			t.start();
		}
		listenersMap.entrySet().forEach( ( entry ) -> {
			entry.getValue().start();
		} );
	}

	private PollingThread pollingThread = null;

	private PollingThread pollingThread()
	{
		synchronized( this ) {
			if ( pollingThread == null ) {
				pollingThread = new PollingThread();
				pollingThread.start();
			}
		}
		return pollingThread;
	}

	private class PollingThread extends Thread
	{

		private final Set< CommChannel> channels = new HashSet<>();

		private PollingThread()
		{
			super( threadGroup, interpreter.programFilename() + "-PollingThread" );
		}

		@Override
		public void run()
		{
			Iterator< CommChannel> it;
			CommChannel channel;
			while( active ) {
				synchronized( this ) {
					if ( channels.isEmpty() ) {
						// Do not busy-wait for no reason
						try {
							this.wait();
						} catch( InterruptedException e ) {
						}
					}
					it = channels.iterator();
					while( it.hasNext() ) {
						channel = it.next();
						try {
							if ( ((PollableCommChannel) channel).isReady() ) {
								it.remove();
								scheduleReceive( channel, channel.parentInputPort() );
							}
						} catch( IOException e ) {
							e.printStackTrace();
						}
					}
				}
				try {
					Thread.sleep( 50 ); // msecs
				} catch( InterruptedException e ) {
				}
			}

			channels.forEach( ( c ) -> {
				try {
					c.closeImpl();
				} catch( IOException e ) {
					interpreter.logWarning( e );
				}
			} );
		}

		public void register( CommChannel channel )
			throws IOException
		{
			if ( !(channel instanceof PollableCommChannel) ) {
				throw new IOException( "Channels registering for polling must implement PollableCommChannel interface" );
			}

			synchronized( this ) {
				channels.add( channel );
				if ( channels.size() == 1 ) { // set was empty
					this.notify();
				}
			}
		}
	}

	/**
	 * Registers a <code>CommChannel</code> for input polling. The registered channel must implement the {@link PollableCommChannel
	 * <code>PollableCommChannel</code>} interface.
	 *
	 * @param channel the channel to register for polling
	 * @throws java.io.IOException in case the channel could not be registered for polling
	 * @see CommChannel
	 * @see PollableCommChannel
	 */
	public void registerForPolling( CommChannel channel )
		throws IOException
	{
		pollingThread().register( channel );
	}

	private final SelectorThread[] selectorThreads;

	private class SelectorThread extends NativeJolieThread
	{

		// We use a custom class for debugging purposes (the profiler gives us the class name)
		private class SelectorMutex extends Object
		{
		}

		private final Selector selector;
		private final SelectorMutex selectingMutex = new SelectorMutex();
		private final Deque< Runnable> selectorTasks = new ArrayDeque<>();

		public SelectorThread( Interpreter interpreter )
			throws IOException
		{
			super( interpreter, threadGroup, interpreter.programFilename() + "-SelectorThread" );
			this.selector = Selector.open();
		}

		private Deque< Runnable> runKeys( SelectionKey[] selectedKeys )
			throws IOException
		{
			boolean keepRun;
			synchronized( this ) {
				do {
					for( final SelectionKey key : selectedKeys ) {
						if ( key.isValid() ) {
							final SelectableStreamingCommChannel channel = (SelectableStreamingCommChannel) key.attachment();
							if ( channel.lock.tryLock() ) {
								key.cancel();
								selectorTasks.add( () -> {
									try {
										try {
											try {
												key.channel().configureBlocking( true );
												if ( channel.isOpen() ) {
													/*if ( channel.selectionTimeoutHandler() != null ) {
														interpreter.removeTimeoutHandler( channel.selectionTimeoutHandler() );
													}*/
													scheduleReceive( channel, channel.parentInputPort() );
												} else {
													channel.closeImpl();
												}
											} catch( ClosedChannelException e ) {
												channel.closeImpl();
											}
										} catch( IOException e ) {
											throw e;
										} finally {
											channel.lock.unlock();
										}
									} catch( IOException e ) {
										if ( channel.lock.isHeldByCurrentThread() ) {
											channel.lock.unlock();
										}
										interpreter.logWarning( e );
									}
								} );
							}
						}
					}
					synchronized( selectingMutex ) {
						if ( selector.selectNow() > 0 ) { // Clean up the cancelled keys
							// If some new channels are selected, run again
							selectedKeys = selector.selectedKeys().toArray( new SelectionKey[ 0 ] );
							keepRun = true;
						} else {
							keepRun = false;
						}
					}
				} while( keepRun );
			}
			return selectorTasks;
		}

		private void runTasks( Deque< Runnable> tasks )
			throws IOException
		{
			Runnable r;
			while( (r = tasks.poll()) != null ) {
				r.run();
			}
		}

		@Override
		public void run()
		{
			while( active ) {
				try {
					SelectionKey[] selectedKeys;
					synchronized( selectingMutex ) {
						selector.select();
						selectedKeys = selector.selectedKeys().toArray( new SelectionKey[ 0 ] );
					}
					final Deque< Runnable> tasks = runKeys( selectedKeys );
					runTasks( tasks );
				} catch( IOException e ) {
					interpreter.logSevere( e );
				}
			}

			synchronized( this ) {
				for( SelectionKey key : selector.keys() ) {
					try {
						((SelectableStreamingCommChannel) key.attachment()).closeImpl();
					} catch( IOException e ) {
						interpreter.logWarning( e );
					}
				}
			}
		}

		public void register( SelectableStreamingCommChannel channel, int index )
		{
			try {
				if ( channel.inputStream().available() > 0 ) {
					scheduleReceive( channel, channel.parentInputPort() );
					return;
				}

				synchronized( this ) {
					if ( !isSelecting( channel ) ) {
						selector.wakeup();
						SelectableChannel c = channel.selectableChannel();
						c.configureBlocking( false );
						synchronized( selectingMutex ) {
							c.register( selector, SelectionKey.OP_READ, channel );
							selector.wakeup();
							channel.setSelectorIndex( index );
						}
					}
				}
			} catch( ClosedChannelException e ) {
				interpreter.logWarning( e );
			} catch( IOException e ) {
				interpreter.logSevere( e );
			}
		}

		public void unregister( SelectableStreamingCommChannel channel )
			throws IOException
		{
			synchronized( this ) {
				if ( isSelecting( channel ) ) {
					selector.wakeup();
					synchronized( selectingMutex ) {
						SelectionKey key = channel.selectableChannel().keyFor( selector );
						if ( key != null ) {
							key.cancel();
						}
						selector.selectNow();
					}
					channel.selectableChannel().configureBlocking( true );
				}
			}
		}
	}

	protected boolean isSelecting( SelectableStreamingCommChannel channel )
	{
		SelectableChannel c = channel.selectableChannel();
		return c != null && c.isRegistered();
	}

	protected void unregisterForSelection( SelectableStreamingCommChannel channel )
		throws IOException
	{
		selectorThreads[ channel.selectorIndex() ].unregister( channel );
	}

	private final AtomicInteger nextSelector = new AtomicInteger( 0 );

	protected void registerForSelection( final SelectableStreamingCommChannel channel )
		throws IOException
	{
		final int i = nextSelector.getAndIncrement() % selectorThreads.length;
		selectorThreads[ i ].register( channel, i );
		/*final TimeoutHandler handler = new TimeoutHandler( interpreter.persistentConnectionTimeout() ) {
			@Override
			public void onTimeout()
			{
				try {
					if ( isSelecting( channel ) ) {
						selectorThread().unregister( channel );
						channel.setToBeClosed( true );
						channel.close();
					}
				} catch( IOException e ) {
					interpreter.logSevere( e );
				}
			}
		};
		channel.setSelectionTimeoutHandler( handler );
		if ( selectorThread().register( channel ) ) {
			interpreter.addTimeoutHandler( handler );
		} else {
			channel.setSelectionTimeoutHandler( null );
		}*/
	}

	/**
	 * Shutdowns the communication core, interrupting every
	 * communication-related thread.
	 */
	public synchronized void shutdown()
	{
		if ( active ) {
			active = false;
			listenersMap.entrySet().forEach( ( entry ) -> {
				entry.getValue().shutdown();
			} );

			for( SelectorThread t : selectorThreads ) {
				t.selector.wakeup();
				try {
					t.join();
				} catch( InterruptedException e ) {
				}
			}

			try {
				channelHandlersLock.writeLock().tryLock( CHANNEL_HANDLER_TIMEOUT, TimeUnit.SECONDS );
			} catch( InterruptedException e ) {
			}
			executorService.shutdown();
			try {
				executorService.awaitTermination( interpreter.persistentConnectionTimeout(), TimeUnit.MILLISECONDS );
			} catch( InterruptedException e ) {
			}
			threadGroup.interrupt();
		}
	}

	private boolean active = false;
}
