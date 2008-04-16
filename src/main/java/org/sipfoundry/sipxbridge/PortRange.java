package org.sipfoundry.sipxbridge;

import java.util.Map;

import org.apache.commons.beanutils.PropertyUtils;

public class PortRange {

    private int lowerBound;

    private int higherBound;

    /**
     * @param lowerBound
     *            the lowerBound to set
     */
    public void setLowerBound(int lowerBound) {
        this.lowerBound = lowerBound;
    }

    /**
     * @return the lowerBound
     */
    public int getLowerBound() {
        return lowerBound;
    }

    /**
     * @param higherBound
     *            the higherBound to set
     */
    public void setHigherBound(int higherBound) {
        this.higherBound = higherBound;
    }

    /**
     * @return the higherBound
     */
    public int getHigherBound() {
        return higherBound;
    }

    public Map toMap() {
        try {
            Map retval = PropertyUtils.describe(this);
            retval.remove("class");
            return retval;
        } catch (Exception ex) {
            throw new RuntimeException("Error generating map", ex);
        }
    }

}