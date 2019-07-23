/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.path;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

public class FHIRPathDecimalValue extends FHIRPathAbstractNode implements FHIRPathNumberValue {
    private final BigDecimal decimal;
    
    protected FHIRPathDecimalValue(Builder builder) {
        super(builder);
        decimal = builder.decimal;
    }
    
    @Override
    public boolean isDecimalValue() {
        return true;
    }
    
    @Override
    public BigDecimal decimal() {
        return decimal;
    }
    
    @Override
    public Integer integer() {
        return decimal.intValue();
    }
    
    public static FHIRPathDecimalValue decimalValue(BigDecimal decimal) {
        return FHIRPathDecimalValue.builder(decimal).build();
    }
    
    public static FHIRPathDecimalValue decimalValue(String name, BigDecimal decimal) {
        return FHIRPathDecimalValue.builder(decimal).name(name).build();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(type, decimal);
    }
    
    public static Builder builder(BigDecimal decimal) {
        return new Builder(FHIRPathType.SYSTEM_DECIMAL, decimal);
    }
    
    public static class Builder extends FHIRPathAbstractNode.Builder {
        private final BigDecimal decimal;
        
        private Builder(FHIRPathType type, BigDecimal decimal) {
            super(type);
            this.decimal = decimal;
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
        public FHIRPathDecimalValue build() {
            return new FHIRPathDecimalValue(this);
        }
    }

    @Override
    public FHIRPathNumberValue add(FHIRPathNumberValue value) {
        return decimalValue(decimal.add(value.decimal()));
    }

    @Override
    public FHIRPathNumberValue subtract(FHIRPathNumberValue value) {
        return decimalValue(decimal.subtract(value.decimal()));
    }

    @Override
    public FHIRPathNumberValue multiply(FHIRPathNumberValue value) {
        return decimalValue(decimal.multiply(value.decimal()));
    }

    @Override
    public FHIRPathNumberValue divide(FHIRPathNumberValue value) {
        return decimalValue(decimal.divide(value.decimal()));
    }

    @Override
    public FHIRPathNumberValue div(FHIRPathNumberValue value) {
        return decimalValue(decimal.divideToIntegralValue(value.decimal()));
    }

    @Override
    public FHIRPathNumberValue mod(FHIRPathNumberValue value) {
        return decimalValue(decimal.remainder(value.decimal()));
    }

    @Override
    public int compareTo(FHIRPathNode other) {
        if (!isComparableTo(other)) {
            throw new IllegalArgumentException();
        }
        if (other instanceof FHIRPathQuantityNode) {
            return decimal.compareTo(((FHIRPathQuantityNode) other).getQuantityValue());
        }
        FHIRPathNumberValue value = (FHIRPathNumberValue) other;
        return decimal.compareTo(value.decimal());
    }
    
    @Override
    public FHIRPathNumberValue negate() {
        return decimalValue(decimal.negate());
    }

    @Override
    public FHIRPathNumberValue plus() {
        return this;
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
        FHIRPathDecimalValue other = (FHIRPathDecimalValue) obj;
        return Objects.equals(decimal, other.decimal());
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(decimal);
    }

    @Override
    public String toString() {
        return decimal.toPlainString();
    }
}