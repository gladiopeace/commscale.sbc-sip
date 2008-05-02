package org.sipfoundry.sipxbridge;
import java.util.Map;

import org.sipfoundry.sipxbridge.SymEndpointInterface;
import org.sipfoundry.sipxbridge.SymInterface;


class SymImpl implements SymInterface {
    String id;
    
    SymEndpointInterface receiver;
    
    SymEndpointInterface transmitter;
    
 

    public String getId() {
        
        return id;
    }

    public SymEndpointInterface getReceiver() {
       
        return receiver;
    }

    public SymEndpointInterface getTransmitter() {
       
        return transmitter;
    }

  
}