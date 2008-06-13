/*
 *  Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 *  Contributors retain copyright to elements licensed under a Contributor Agreement.
 *  Licensed to the User under the LGPL license.
 *
 */
package org.sipfoundry.sipxbridge.symmitron;

import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A media endpoint is an ip addres, port pair.
 * 
 * @author M. Ranganathan
 * 
 */
public abstract class SymEndpoint implements SymEndpointInterface {

    private String id;

    /*
     * IP address
     */
    protected String ipAddress;

    /*
     * My port
     */
    protected int port;

    /*
     * Our datagram channel.
     */
    protected  DatagramChannel datagramChannel;

    private Sym sym;
 
    public Map<String,Object> toMap() {
       
            Map<String,Object> retval = new HashMap<String,Object>();
            retval.put("id", this.getId());
            retval.put("ipAddress", ipAddress);
            retval.put("port", new Integer(port) );
            return retval;
        

    }

    /**
     * Constructor sym endpoint.
     * 
     * @param isTransmitter -- whether or not this is a transmitter.
     * @param rtpPortLowerBound -- where to start counting for port allocation.
     * 
     */
    protected SymEndpoint()  {

        this.id = "sym-endpoint:" + Math.abs(new Random().nextLong());
    }

    /**
     * Get the unique ID for this endpoint.
     */
    public String getId() {
        return this.id;
    }

   

    /**
     * Get our RTP datagram channel.
     */
    public DatagramChannel getDatagramChannel() {
        return datagramChannel;
    }

    

   
  
    /**
     * Get the IP address associated with the endpoint ( transmitter will have the remote Ip address here).
     */

    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * Get the port associated with the endpoint ( transmitter will have the remote port here ).
     * 
     */
    public int getPort() {
        return port;
    }

    

    /**
     * @param sym
     *            the rtpSession to set
     */
    public void setSym(Sym sym) {
        this.sym = sym;
    }

    /**
     * @return the rtpSession
     */
    public Sym getSym() {
        return sym;
    }

    /**
     * Set the ip address - the IP address will be either the local listen 
     * address or the remote address ( for transmitter).
     * 
     * @param ipAddress
     */
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    
    /**
     * Set the port - the port will be either the local listen port or the remote port
     * (for the transmitter ).
     * 
     * @param port
     */
    public void setPort(int port) throws IllegalArgumentException {
        if ( port < 0 ) throw new IllegalArgumentException("Bad port "+ port);
        this.port = port;
    }

}