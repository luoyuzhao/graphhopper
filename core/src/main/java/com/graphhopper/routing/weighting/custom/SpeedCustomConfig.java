/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.profiles.BooleanEncodedValue;
import com.graphhopper.routing.profiles.DecimalEncodedValue;
import com.graphhopper.routing.profiles.EncodedValueLookup;
import com.graphhopper.routing.profiles.EnumEncodedValue;
import com.graphhopper.routing.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.graphhopper.routing.weighting.custom.PriorityCustomConfig.getEV;

final class SpeedCustomConfig {
    private List<EdgeToValueEntry> speedFactorList = new ArrayList<>();
    private List<EdgeToValueEntry> maxSpeedList = new ArrayList<>();
    private DecimalEncodedValue avgSpeedEnc;
    private final double maxSpeed;
    private final double maxSpeedFallback;

    public SpeedCustomConfig(final double maxSpeed, CustomModel customModel, DecimalEncodedValue avgSpeedEnc,
                             EncodedValueLookup lookup) {
        this.maxSpeed = maxSpeed;
        this.maxSpeedFallback = customModel.getMaxSpeedFallback() == null ? maxSpeed : customModel.getMaxSpeedFallback();
        this.avgSpeedEnc = avgSpeedEnc;
        if (this.maxSpeedFallback > maxSpeed)
            throw new IllegalArgumentException("max_speed_fallback cannot be bigger than max_speed " + maxSpeed);

        // use max_speed to lower speed for the specified conditions
        for (Map.Entry<String, Object> entry : customModel.getMaxSpeed().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Number) {
                if (key.startsWith(GeoToValueEntry.key(""))) {
                    Geometry geometry = GeoToValueEntry.pickGeometry(customModel, key);
                    maxSpeedList.add(new GeoToValueEntry(new PreparedGeometryFactory().create(geometry), ((Number) value).doubleValue(), maxSpeed));
                } else {
                    BooleanEncodedValue encodedValue = getEV(lookup, "max_speed", key, BooleanEncodedValue.class);
                    maxSpeedList.add(new BooleanToValueEntry(encodedValue, ((Number) value).doubleValue(), maxSpeed));
                }
            } else if (value instanceof Map) {
                EnumEncodedValue enumEncodedValue = getEV(lookup, "max_speed", key, EnumEncodedValue.class);
                Enum[] enumValues = enumEncodedValue.getValues();
                double[] values = Helper.createEnumToDoubleArray("max_speed." + key, maxSpeed, 0, maxSpeed,
                        enumValues, (Map<String, Object>) value);
                maxSpeedList.add(new EnumToValueEntry(enumEncodedValue, values));
            } else {
                throw new IllegalArgumentException("Type " + value.getClass() + " is not supported for 'max_speed'");
            }
        }

        // use speed_factor to reduce the estimated speed value under the specified conditions
        for (Map.Entry<String, Object> entry : customModel.getSpeedFactor().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Number) {
                if (key.startsWith(GeoToValueEntry.key(""))) {
                    Geometry geometry = GeoToValueEntry.pickGeometry(customModel, key);
                    speedFactorList.add(new GeoToValueEntry(new PreparedGeometryFactory().create(geometry), ((Number) value).doubleValue(), 1));
                } else {
                    BooleanEncodedValue encodedValue = getEV(lookup, "speed_factor", key, BooleanEncodedValue.class);
                    speedFactorList.add(new BooleanToValueEntry(encodedValue, ((Number) value).doubleValue(), 1));
                }
            } else if (value instanceof Map) {
                EnumEncodedValue enumEncodedValue = getEV(lookup, "speed_factor", key, EnumEncodedValue.class);
                Enum[] enumValues = enumEncodedValue.getValues();
                double[] values = Helper.createEnumToDoubleArray("speed_factor." + key, 1, 0, 1,
                        enumValues, (Map<String, Object>) value);
                speedFactorList.add(new EnumToValueEntry(enumEncodedValue, values));
            } else {
                throw new IllegalArgumentException("Type " + value.getClass() + " is not supported for 'speed_factor'");
            }
        }
    }

    public double getMaxSpeed() {
        return maxSpeed;
    }

    /**
     * @return speed in km/h
     */
    public double calcSpeed(EdgeIteratorState edge, boolean reverse) {
        double speed = reverse ? edge.getReverse(avgSpeedEnc) : edge.get(avgSpeedEnc);
        if (Double.isInfinite(speed) || Double.isNaN(speed) || speed < 0)
            throw new IllegalStateException("Invalid estimated speed " + speed);

        for (int i = 0; i < speedFactorList.size(); i++) {
            EdgeToValueEntry entry = speedFactorList.get(i);
            double factorValue = entry.getValue(edge, reverse);
            speed *= factorValue;
            if (speed <= 0)
                break;
        }

        boolean applied = false;
        for (int i = 0; i < maxSpeedList.size(); i++) {
            EdgeToValueEntry entry = maxSpeedList.get(i);
            double maxValue = entry.getValue(edge, reverse);
            if (maxValue < speed) {
                applied = true;
                speed = maxValue;
            }
        }

        if (!applied && maxSpeedFallback < speed)
            return maxSpeedFallback;

        return Math.min(speed, maxSpeed);
    }
}
