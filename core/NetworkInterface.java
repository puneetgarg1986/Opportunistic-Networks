/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package core;

import interfaces.ConnectivityGrid;

import interfaces.ConnectivityOptimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import routing.util.EnergyModel;

import util.ActivenessHandler;

/**
 * Network interface of a DTNHost. Takes care of connectivity among hosts.
 */
abstract public class NetworkInterface implements ModuleCommunicationListener {
	/** transmit range -setting id ({@value})*/
	public static final String TRANSMIT_RANGE_S = "transmitRange";
	/** transmit speed -setting id ({@value})*/
	public static final String TRANSMIT_SPEED_S = "transmitSpeed";
	/** scanning interval -setting id ({@value})*/
	public static final String SCAN_INTERVAL_S = "scanInterval";

	/** 
	 * Sub-namespace for the network related settings in the Group namespace
	 * ({@value}) 
	 */
	public static final String NET_SUB_NS = "net";
	
	/** Activeness offset jitter -setting id ({@value})
	 * The maximum amount of random offset for the offset */
	public static final String ACT_JITTER_S = "activenessOffsetJitter";
	
	
	/** {@link ModuleCommunicationBus} identifier for the "scanning interval"
    variable. */
	public static final String SCAN_INTERVAL_ID = "Network.scanInterval";
	
	/** {@link ModuleCommunicationBus} identifier for the "radio range"
	variable. Value type: double */
	public static final String RANGE_ID = "Network.radioRange";
	/** {@link ModuleCommunicationBus} identifier for the "transmission speed"
    variable. Value type: integer */
	public static final String SPEED_ID = "Network.speed";

	private static final int CON_UP = 1;
	private static final int CON_DOWN = 2;
	private static int nextAddress = 0;
	private static Random rng;
	protected DTNHost host = null;

	protected String interfacetype;
	protected List<Connection> connections; // connected hosts
	private List<ConnectionListener> cListeners = null; // list of listeners
	private int address; // network interface address
        protected double transmitRange;
        // eu adicionei (para atualizar as interfaces de acordo com nivel de energia)
        protected double oldTransmitRange;

        protected double initialTransmitRange;
	protected int transmitSpeed;
	protected ConnectivityOptimizer optimizer = null;
	/** scanning interval, or 0.0 if n/a */
	private double scanInterval;
	private double lastScanTime;
	
	/** activeness handler for the node group */
	private ActivenessHandler ah;
	/** maximum activeness jitter value for the node group */
	private int activenessJitterMax;
	/** this interface's activeness jitter value */
	private int activenessJitterValue;
	
	
        //eu adicionei
        private double inter_on_off;


	static {
		DTNSim.registerForReset(NetworkInterface.class.getCanonicalName());
		reset();
	}

	/**
	 * Resets the static fields of the class
	 */

	public static void reset() {
		nextAddress = 0;
		rng = new Random(0);

	}

	/**
	 * For creating an empty class of a specific type
	 */
	public NetworkInterface(Settings s) {
		this.interfacetype = s.getNameSpace();
		this.connections = new ArrayList<Connection>();
		this.address = getNextNetAddress();


		this.transmitRange = s.getDouble(TRANSMIT_RANGE_S);
		this.transmitSpeed = s.getInt(TRANSMIT_SPEED_S);
		ensurePositiveValue(transmitRange, TRANSMIT_RANGE_S);
		ensurePositiveValue(transmitSpeed, TRANSMIT_SPEED_S);
		
		
		if (s.contains(SCAN_INTERVAL_S)) {
			this.scanInterval =  s.getDouble(SCAN_INTERVAL_S);
		} else {
			this.scanInterval = 0.0;
		}
                //eu adicionei (pega o estado inicial das interfaces - on/off)
                if (s.getDouble(TRANSMIT_RANGE_S) > 0){
                        this.inter_on_off = 1.0;
                } else {
                        this.inter_on_off = 0.0;
                }
                //System.out.println("Construtor 1: scanInterval= "+scanInterval);
	}

	/**
	 * For creating an empty class of a specific type
	 */
	public NetworkInterface() {
		this.interfacetype = "Default";
		this.connections = new ArrayList<Connection>();
		this.address = getNextNetAddress();
                //System.out.println("Construtor 2: scanInterval= "+scanInterval);
	}

	/**
	 * copy constructor
	 */
	public NetworkInterface(NetworkInterface ni) {
		this.connections = new ArrayList<Connection>();
		this.address = getNextNetAddress();
		this.host = ni.host;
		this.cListeners = ni.cListeners;
		this.interfacetype = ni.interfacetype;
		this.transmitRange = ni.transmitRange;
		this.transmitSpeed = ni.transmitSpeed;
		this.ah = ni.ah;
          
                //eu adicionei (estado inicial das interfaces - > 0 = on, < 0 = off)
                this.inter_on_off = ni.inter_on_off;
                
                if (ni.activenessJitterMax > 0) {
        			this.activenessJitterValue = rng.nextInt(ni.activenessJitterMax);
        		} else {
        			this.activenessJitterValue = 0;
        		}

                              
                //eu adicionei (bug reportado polr mathew)             
                
                
                this.scanInterval = ni.scanInterval;
                // eu adicionei
              
                this.initialTransmitRange = this.transmitRange;
		

		/* draw lastScanTime of [0 -- scanInterval] */
		this.lastScanTime = rng.nextDouble() * scanInterval;
                //System.out.println("Construtor 3: scanInterval= "+scanInterval);
                //System.out.println("lastScanTime: "+this.lastScanTime);
	}

	/**
	 * Replication function
	 */
	abstract public NetworkInterface replicate();

	/**
	 * For setting the host - needed when a prototype is copied for several
	 * hosts
	 * @param host The host where the network interface is
	 */
	public void setHost(DTNHost host) {
		this.host = host;
		ModuleCommunicationBus comBus = host.getComBus();
		
		if (!comBus.containsProperty(SCAN_INTERVAL_ID) &&
			    !comBus.containsProperty(RANGE_ID)) {
				/* add properties and subscriptions only for the 1st interface */
				/* TODO: support for multiple interfaces */
				comBus.addProperty(SCAN_INTERVAL_ID, this.scanInterval);
				comBus.addProperty(RANGE_ID, this.transmitRange);
				comBus.addProperty(SPEED_ID, this.transmitSpeed);
				
		comBus.subscribe(SCAN_INTERVAL_ID, this);
		comBus.subscribe(RANGE_ID, this);
		comBus.subscribe(SPEED_ID, this);
		}
		
		if (transmitRange > 0) {

		optimizer = ConnectivityGrid.ConnectivityGridFactory(
				this.interfacetype.hashCode(), transmitRange);
		optimizer.addInterface(this);
	      }
		else
		{
			optimizer = null;
		}
	}

	/**
	 * Sets group-based settings for the network interface
	 * @param s The settings object using the right group namespace
	 */
	public void setGroupSettings(Settings s) {
		s.setSubNameSpace(NET_SUB_NS);	
		ah = new ActivenessHandler(s);
		
		if (s.contains(SCAN_INTERVAL_S)) {
			this.scanInterval =  s.getDouble(SCAN_INTERVAL_S);
		} else {
			this.scanInterval = 0;
		}
		if (s.contains(ACT_JITTER_S)) {
			this.activenessJitterMax = s.getInt(ACT_JITTER_S);
		}
		
		s.restoreSubNameSpace();
	}

	
	
	/**
	 * For checking what interface type this interface is
	 */
	public String getInterfaceType() {
		return interfacetype;
	}

	/**
	 * For setting the connectionListeners
	 * @param cListeners List of connection listeners
	 */
	public void setClisteners(List<ConnectionListener> cListeners) {
		this.cListeners = cListeners;
	}

	/**
	 * Returns a new network interface address and increments the address for
	 * subsequent calls.
	 * @return The next address.
	 */
	private synchronized static int getNextNetAddress() {
		return nextAddress++;
	}

	/**
	 * Returns the network interface address.
	 * @return The address (integer)
	 */
	public int getAddress() {
		return this.address;
	}

	/**
	 * Returns the transmit range of this network layer
	 * @return the transmit range
	 */
	public double getTransmitRange() {
		return this.transmitRange;
	}

	/**
	 * Returns the transmit speed of this network layer
	 * @return the transmit speed
	 */
	public int getTransmitSpeed() {
		return this.transmitSpeed;
	}
	
	/**
	 * Returns the transmit speed of this network layer with respect to the
	 * another network interface
	 * @param ni The other network interface
	 * @return the transmit speed
	 */
	public int getTransmitSpeed(NetworkInterface ni) {
		return this.transmitSpeed;
	}


	/**
	 * Returns a list of currently connected connections
	 * @return a list of currently connected connections
	 */
	public List<Connection> getConnections() {
		return this.connections;
	}
	
	/**
	 * Returns true if the interface is on at the moment (false if not)
	 * @return true if the interface is on at the moment (false if not)
	 */
	public boolean isActive() {
		boolean active;
		
		if (ah == null) {
			return true; /* no handler: always active */
		}
		
		active = ah.isActive(this.activenessJitterValue);
		
		if (active && host.getComBus().getDouble(EnergyModel.ENERGY_VALUE_ID,
					1) <= 0) {
			/* TODO: better way to check battery level */
			/* no battery -> inactive */
			active = false;
		}
		
		if (active == false && this.transmitRange > 0) {
			/* not active -> make range 0 */
			this.oldTransmitRange = this.transmitRange;
			host.getComBus().updateProperty(RANGE_ID, 0.0);
		} else if (active == true && this.transmitRange == 0.0) {
			/* active, but range == 0 -> restore range  */
			host.getComBus().updateProperty(RANGE_ID, 
					this.oldTransmitRange);
		}		
		return active;
	}
	

	/**
	 * Checks if this interface is currently in the scanning mode
	 * @return True if the interface is scanning; false if not
	 */
	public boolean isScanning() {
		double simTime = SimClock.getTime();
                //em teste
                //System.out.println("Estado da interface: " + inter_on_off);
		//System.out.println("IsScannig: simTime= "+simTime+" scanInterval= "+scanInterval);
		if (scanInterval > 0.0) {
			if (simTime < lastScanTime) {
                                //em teste
                               // System.out.println("Entrei no primeiro false "+simTime);
                                //System.out.println(" ");
				return false; /* not time for the first scan */
			}
			else if (simTime > lastScanTime + scanInterval)  { //eu adicionei
				lastScanTime = simTime; /* time to start the next scan round */
                                if (inter_on_off != 0)
                                    return true;
                                else
                                    return false;
                                //System.out.println("Entrou no true interno "+simTime);
                                //System.out.println(" ");
				//return true;
			}
			else if (simTime != lastScanTime ){
                                //em teste
                                //System.out.println("Entrei no segundo false "+simTime);
                                //System.out.println(" ");
				return false; /* not in the scan round */
			}
		}
		/* interval == 0 or still in the last scan round */
                //System.out.println("Entrou no True externo "+simTime);
                //System.out.println("scanInterval: " + scanInterval);
                //System.out.println("lastScanTime "+lastScanTime);
                if (inter_on_off != 0) {
                  //  System.out.println("Scanning ligado");
                    //System.out.println(" ");
                    return true;
                } else {
                  //  System.out.println("Scanning deligado");
                    //System.out.println(" ");
                    return false;
                }
                //return true;
	}
	
	
	/**
	 * Returns true if one of the connections of this interface is transferring
	 * data
	 * @return true if the interface transferring
	 */
	public boolean isTransferring() {
		for (Connection c : this.connections) {
			if (c.isTransferring()) {
				return true;
			}
		}
		return false;
	}


	/**
	 * Connects the interface to another interface.
	 *
	 * Overload this in a derived class.  Check the requirements for
	 * the connection to work in the derived class, then call
	 * connect(Connection, NetworkInterface) for the actual connection.
	 * @param anotherInterface The interface to connect to
	 */
	public abstract void connect(NetworkInterface anotherInterface);

	/**
	 * Connects this host to another host. The derived class should check
	 * that all pre-requisites for making a connection are satisfied before
	 * actually connecting.
	 * @param con The new connection object
	 * @param anotherInterface The interface to connect to
	 */
	protected void connect(Connection con, NetworkInterface anotherInterface) {

		this.connections.add(con);
		notifyConnectionListeners(CON_UP, anotherInterface.getHost());

		// set up bidirectional connection
		anotherInterface.getConnections().add(con);

		// inform routers about the connection
		this.host.connectionUp(con);
		anotherInterface.getHost().connectionUp(con);
	}

	/**
	 * Disconnects this host from another host.  The derived class should
	 * make the decision whether to disconnect or not
	 * @param con The connection to tear down
	 */
	protected void disconnect(Connection con,
			NetworkInterface anotherInterface) {
		con.setUpState(false);
		notifyConnectionListeners(CON_DOWN, anotherInterface.getHost());

		// tear down bidirectional connection
		if (!anotherInterface.getConnections().remove(con)) {
			throw new SimError("No connection " + con + " found in " +
					anotherInterface);
		}

		this.host.connectionDown(con);
		anotherInterface.getHost().connectionDown(con);
	}

	/**
	 * Returns true if another interface is within radio range of this interface
	 * and this interface is also within radio range of the another interface.
	 * @param anotherInterface The another interface
	 * @return True if the interface is within range, false if not
	 */
	protected boolean isWithinRange(NetworkInterface anotherInterface) {
		double smallerRange = anotherInterface.getTransmitRange();
		double myRange = getTransmitRange();
		if (myRange < smallerRange) {
			smallerRange = myRange;
		}

		return this.host.getLocation().distance(
				anotherInterface.getHost().getLocation()) <= smallerRange;
	}

	/**
	 * Returns true if the given NetworkInterface is connected to this host.
	 * @param netinterface The other NetworkInterface to check
	 * @return True if the two hosts are connected
	 */
	protected boolean isConnected(NetworkInterface netinterface) {
		for (int i = 0; i < this.connections.size(); i++) {
			if (this.connections.get(i).getOtherInterface(this) ==
				netinterface) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Makes sure that a value is positive
	 * @param value Value to check
	 * @param settingName Name of the setting (for error's message)
	 * @throws SettingsError if the value was not positive
	 */
	protected void ensurePositiveValue(double value, String settingName) {
		if (value < 0) {
			throw new SettingsError("Negative value (" + value +
					") not accepted for setting " + settingName);
		}
	}

	/**
	 * Updates the state of current connections (ie tears down connections
	 * that are out of range, recalculates transmission speeds etc.).
	 */
	abstract public void update();

	/**
	 * Notifies all the connection listeners about a change in connections.
	 * @param type Type of the change (e.g. {@link #CON_DOWN} )
	 * @param otherHost The other host on the other end of the connection.
	 */
	private void notifyConnectionListeners(int type, DTNHost otherHost) {
		if (this.cListeners == null) {
			return;
		}
		for (ConnectionListener cl : this.cListeners) {
			switch (type) {
			case CON_UP:
				cl.hostsConnected(this.host, otherHost);
				break;
			case CON_DOWN:
				cl.hostsDisconnected(this.host, otherHost);
				break;
			default:
				assert false : type;	// invalid type code
			}
		}
	}

	/**
	 * This method is called by the {@link ModuleCommunicationBus} when/if
	 * someone changes the scanning interval, transmit speed, or range
	 * @param key Identifier of the changed value
	 * @param newValue New value for the variable
	 */
	public void moduleValueChanged(String key, Object newValue) {
		if (key.equals(SCAN_INTERVAL_ID)) {
			this.scanInterval = (Double)newValue;
		}
		else if (key.equals(SPEED_ID)) {
			this.transmitSpeed = (Integer)newValue;
		}
		else if (key.equals(RANGE_ID)) {
                    // eu adicionei (desliga/liga interfaces conforme nivel de energia)
                    if ((Double)newValue != 0.0) {
                        //System.out.println(this.toString() + " ON transmitRange = " + this.transmitRange);
                        this.transmitRange = this.initialTransmitRange;
                        //em teste (interface ligada, pode fazer scanner)
                        inter_on_off = 1;
                        //System.out.println("Interface ligada " + inter_on_off);

                    } else {
                        //System.out.println(this.toString() + " OFF transmitRange = " + this.transmitRange);
                        this.transmitRange = 0.0;
                        //em teste (interface desligada, não pode fazer scanner)
                        inter_on_off = 0;
                        //System.out.println("Interface desligada " + inter_on_off);
                    }
		}
		else {
			throw new SimError("Unexpected combus ID " + key);
		}

	}

	/**
	 * Creates a connection to another host. This method does not do any checks
	 * on whether the other node is in range or active
	 * (cf. {@link #connect(NetworkInterface)}).
	 * @param anotherInterface The interface to create the connection to
	 */
	public abstract void createConnection(NetworkInterface anotherInterface);

	/**
	 * Disconnect a connection between this and another host.
	 * @param anotherInterface The other host's network interface to disconnect
	 * from this host
	 */
	public void destroyConnection(NetworkInterface anotherInterface) {
		DTNHost anotherHost = anotherInterface.getHost();
		for (int i=0; i < this.connections.size(); i++) {
			if (this.connections.get(i).getOtherNode(this.host) == anotherHost){
				removeConnectionByIndex(i, anotherInterface);
			}
		}
		// the connection didn't exist, do nothing
	}

	/**
	 * Removes a connection by its position (index) in the connections array
	 * of the interface
	 * @param index The array index of the connection to be removed
	 * @param anotherInterface The interface of the other host
	 */
	private void removeConnectionByIndex(int index,
			NetworkInterface anotherInterface) {
		Connection con = this.connections.get(index);
		DTNHost anotherNode = anotherInterface.getHost();
		con.setUpState(false);
		notifyConnectionListeners(CON_DOWN, anotherNode);

		// tear down bidirectional connection
		if (!anotherInterface.getConnections().remove(con)) {
			throw new SimError("No connection " + con + " found in " +
					anotherNode);
		}

		this.host.connectionDown(con);
		anotherNode.connectionDown(con);

		connections.remove(index);
	}

	/**
	 * Returns the DTNHost of this interface
	 */
	public DTNHost getHost() {
		return host;
	}

	/**
	 * Returns the current location of the host of this interface.
	 * @return The location
	 */
	public Coord getLocation() {
		return host.getLocation();
	}

	/**
	 * Returns a string representation of the object.
	 * @return a string representation of the object.
	 */
	public String toString() {
		return "net interface " + this.address + " of " + this.host +
			". Connections: " +	this.connections;
	}

}

