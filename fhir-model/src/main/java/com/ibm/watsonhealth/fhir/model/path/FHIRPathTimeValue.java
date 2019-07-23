/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.path;

import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getTemporal;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getTemporalAmount;

import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.Collection;
import java.util.Objects;

public class FHIRPathTimeValue extends FHIRPathAbstractNode implements FHIRPathPrimitiveValue {
    private static final DateTimeFormatter TIME_PARSER_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("'T'HH:mm:ss")
            .optionalStart()
                .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .optionalStart()
                .appendPattern("XXX")
            .optionalEnd()
            .toFormatter();
    
    private final TemporalAccessor time;
    
    protected FHIRPathTimeValue(Builder builder) {
        super(builder);
        time = builder.time;
    }
    
    @Override
    public boolean isTimeValue() {
        return true;
    }
    
    public boolean hasTimeZone() {
        return getTimeZone() != null;
    }
    
    public ZoneOffset getTimeZone() {
        if (time instanceof OffsetTime) {
            return ((OffsetTime) time).getOffset();
        }
        return null;
    }
    
    public TemporalAccessor time() {
        return time;
    }
    
    public static FHIRPathTimeValue timeValue(String time) {
        return FHIRPathTimeValue.builder(TIME_PARSER_FORMATTER.parseBest(time, OffsetTime::from, LocalTime::from)).build();
    }
    
    private static FHIRPathTimeValue timeValue(TemporalAccessor time) {
        return FHIRPathTimeValue.builder(time).build();
    }
    
    public static FHIRPathTimeValue timeValue(String name, TemporalAccessor time) {
        return FHIRPathTimeValue.builder(time).name(name).build();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(type, time);
    }
    
    public static Builder builder(TemporalAccessor time) {
        return new Builder(FHIRPathType.SYSTEM_TIME, time);
    }
    
    public static class Builder extends FHIRPathAbstractNode.Builder {
        private final TemporalAccessor time;
        
        private Builder(FHIRPathType type, TemporalAccessor time) {
            super(type);
            this.time = time;
        }
        
        @Override
        public Builder name(String name) {
            return (Builder) super.name(name);
        }
        
        @Override
        public Builder value(FHIRPathPrimitiveValue value) {
            return this;
        }
        
        @Override
        public Builder children(FHIRPathNode... children) {
            return this;
        }
        
        @Override
        public Builder children(Collection<FHIRPathNode> children) {
            return this;
        }

        @Override
        public FHIRPathTimeValue build() {
            return new FHIRPathTimeValue(this);
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        FHIRPathTimeValue other = (FHIRPathTimeValue) obj;
        return Objects.equals(time, other.time());
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(time);
    }

    @Override
    public boolean isComparableTo(FHIRPathNode other) {
        if (other instanceof FHIRPathTimeValue) {
            FHIRPathTimeValue timeValue = (FHIRPathTimeValue) other;
            return (time instanceof LocalTime && timeValue.time instanceof LocalTime) || 
                    (time instanceof OffsetTime && timeValue.time instanceof OffsetTime);
        }
        return false;
    }

    @Override
    public int compareTo(FHIRPathNode other) {
        if (!isComparableTo(other)) {
            throw new IllegalArgumentException();
        }
        FHIRPathTimeValue timeValue = (FHIRPathTimeValue) other;
        if (time instanceof LocalTime) {
            return LocalTime.from(time).compareTo(LocalTime.from(timeValue.time));
        }
        return OffsetTime.from(time).compareTo(OffsetTime.from(timeValue.time));
    }
    
    public FHIRPathTimeValue add(FHIRPathQuantityNode quantityNode) {
        Temporal temporal = getTemporal(time);
        TemporalAmount temporalAmount = getTemporalAmount(quantityNode);
        return timeValue(temporal.plus(temporalAmount));
    }
    
    public FHIRPathTimeValue subtract(FHIRPathQuantityNode quantityNode) {
        Temporal temporal = getTemporal(time);
        TemporalAmount temporalAmount = getTemporalAmount(quantityNode);
        return timeValue(temporal.minus(temporalAmount));
    }
    
    @Override
    public String toString() {
        return TIME_PARSER_FORMATTER.format(time);
    }
}