/*
 * RestMonitor.java - Copyright(c) 2013, 204 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Oct 22, 2014
 */
package org.noroomattheinn.visibletesla.data;

import java.util.Calendar;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import org.noroomattheinn.timeseries.Row;
import org.noroomattheinn.timeseries.RowDescriptor;
import org.noroomattheinn.utils.CalTime;
import org.noroomattheinn.utils.TrackedObject;
import org.noroomattheinn.visibletesla.vehicle.VTVehicle;

/**
 * RestMonitor - Monitor and store data about Rest Cycles.
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
class RestMonitor {
/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/

    private static final long MIN_REST_PERIOD = 60 * 60 * 1000; // 60 Minutes
    
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    private final VTVehicle         vtVehicle;
    private final TrackedObject<RestCycle> lastRestCycle;
    private final RowDescriptor     schema;
    private final Calendar          fromLimit, toLimit;
    private final boolean           stradles;
    private final BooleanProperty   limitEnabled;
    private RestCycle               cycleInProgress = null;
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/

    RestMonitor(VTVehicle v, TrackedObject<RestCycle> lastRestCycle,
            BooleanProperty limitEnabled, ObjectProperty<CalTime> limitFrom,
            ObjectProperty<CalTime> limitTo) {
        this.vtVehicle = v;
        this.lastRestCycle = lastRestCycle;
        this.schema = VTData.schema;
        this.limitEnabled = limitEnabled;
        if (limitEnabled.get()) {
            fromLimit = limitFrom.get();
            toLimit = limitTo.get();
            stradles = (toLimit.before(fromLimit));
        } else {
            fromLimit = toLimit = null;
            stradles = false;
        }
              
        this.cycleInProgress = null;
        vtVehicle.chargeState.addTracker(new Runnable() {
            @Override public void run() {
                handleNewData(StatsCollector.rowFromStates(
                        vtVehicle.chargeState.get(), vtVehicle.streamState.get()));
            }
        });
    }

    void handleNewData(Row r) {
        long timestamp = r.timestamp;

        if (outOfRange(timestamp)) {
            if (cycleInProgress != null) { completeCycle(r); }
            return;
        }

        double speed = r.get(schema, VTData.SpeedKey);
        double voltage = r.get(schema, VTData.VoltageKey);
        boolean idle = (speed == 0 && voltage < 100);
        
        if (cycleInProgress == null ) { // Not in a cycle
            if (idle) { startCycle(r); }
        } else {                        // In the middle of a cycle
            if (idle) { updateCycle(r); }
            else { completeCycle(r); }
        }
    }
    
/*------------------------------------------------------------------------------
 *
 * Private Utility Methods
 * 
 *----------------------------------------------------------------------------*/
    
    private void startCycle(Row r) {
        cycleInProgress = new RestCycle();
        cycleInProgress.startTime = r.timestamp;
        cycleInProgress.startRange = r.get(schema, VTData.EstRangeKey);
        cycleInProgress.startSOC = r.get(schema, VTData.SOCKey);
    }

    private void updateCycle(Row r) {
        cycleInProgress.endTime = r.timestamp;
        cycleInProgress.endRange = r.get(schema, VTData.EstRangeKey);
        cycleInProgress.endSOC = r.get(schema, VTData.SOCKey);
        cycleInProgress.lat = r.get(schema, VTData.LatitudeKey);
        cycleInProgress.lng = r.get(schema, VTData.LongitudeKey);
    }
    
    private void completeCycle(Row r) {
        updateCycle(r);
        if (cycleInProgress.endTime - cycleInProgress.startTime > MIN_REST_PERIOD) {
            // OK, there's another odd situation to handle. If we start
            // a rest period and then stop getting data, we may miss a
            // charge. In that case the rest may look like we gained
            // power instead of losing power. In that case just toss
            // the rest period. It will skew the data.
            if (cycleInProgress.endRange <= cycleInProgress.startRange) {
                lastRestCycle.set(cycleInProgress);
            }
        }
        cycleInProgress = null;        
    }
    
    private boolean outOfRange(long ts) {
        if (!limitEnabled.get()) return false;
        CalTime c = new CalTime(ts);
        if (stradles) { return (c.after(toLimit) && c.before(fromLimit)); }
        else { return c.after(toLimit) || c.before(fromLimit); }
    }

}
