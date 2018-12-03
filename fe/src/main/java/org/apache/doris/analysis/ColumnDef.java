// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import com.google.common.base.Preconditions;
import org.apache.doris.catalog.AggregateType;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.PrimitiveType;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.Type;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.FeNameFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Column definition which is generated by SQL syntax parser
// Syntax:
//      name type [key] [agg_type] [NULL | NOT NULL] [DEFAULT default_value] [comment]
// Example:
//      id bigint key NOT NULL DEFAULT "-1" "user id"
//      pv bigint sum NULL DEFAULT "-1" "page visit"
public class ColumnDef {
    private static final Logger LOG = LogManager.getLogger(ColumnDef.class);
    private static final String HLL_EMPTY_SET = "0";

    // parameter initialized in constructor
    private String name;
    private TypeDef typeDef;
    private AggregateType aggregateType;
    private boolean isKey;
    private boolean isAllowNull;
    private String defaultValue;
    private String comment;

    public ColumnDef(String name, TypeDef typeDef) {
        this.name = name;
        this.typeDef = typeDef;
        this.comment = "";
    }

    public ColumnDef(String name, TypeDef typeDef,
                     boolean isKey, AggregateType aggregateType,
                     boolean isAllowNull, String defaultValue,
                     String comment) {
        this.name = name;
        this.typeDef = typeDef;
        this.isKey = isKey;
        this.aggregateType = aggregateType;
        this.isAllowNull = isAllowNull;
        this.defaultValue = defaultValue;
        this.comment = comment;
    }

    public boolean isAllowNull() { return isAllowNull; }
    public String getDefaultValue() { return defaultValue; }
    public String getName() { return name; }
    public AggregateType getAggregateType() { return aggregateType; }
    public void setAggregateType(AggregateType aggregateType, boolean xxx) { this.aggregateType = aggregateType; }
    public boolean isKey() { return isKey; }
    public void setIsKey(boolean isKey) { this.isKey = isKey; }
    public TypeDef getTypeDef() { return typeDef; }
    public Type getType() { return typeDef.getType(); }

    public void analyze(boolean isOlap) throws AnalysisException {
        if (name == null || typeDef == null) {
            throw new AnalysisException("No column name or column type in column definition.");
        }
        FeNameFormat.checkColumnName(name);
        typeDef.analyze(null);

        Type type = typeDef.getType();
        if (aggregateType != null) {
            // check if aggregate type is valid
            if (!aggregateType.checkCompatibility(type.getPrimitiveType())) {
                throw new AnalysisException(String.format("Aggregate type %s is not compatible with primitive type %s",
                        toString(), type.toSql()));
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.FLOAT || type.getPrimitiveType() == PrimitiveType.DOUBLE) {
            if (isOlap && isKey) {
                throw new AnalysisException("Float or double can not used as a key, use decimal instead.");
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.HLL) {
            if (defaultValue != null) {
                throw new AnalysisException("Hll can not set default value");
            }
            defaultValue = HLL_EMPTY_SET;
        }

        if (defaultValue != null) {
            validateDefaultValue(type, defaultValue);
        }
    }

    public static void validateDefaultValue(Type type, String defaultValue) throws AnalysisException {
        Preconditions.checkNotNull(defaultValue);
        Preconditions.checkArgument(type.isScalarType());
        ScalarType scalarType = (ScalarType) type;

        // check if default value is valid.
        // if not, some literal constructor will throw AnalysisException
        PrimitiveType primitiveType = scalarType.getPrimitiveType();
        switch (primitiveType) {
            case TINYINT:
            case SMALLINT:
            case INT:
            case BIGINT:
                IntLiteral intLiteral = new IntLiteral(defaultValue, type);
                break;
            case LARGEINT:
                LargeIntLiteral largeIntLiteral = new LargeIntLiteral(defaultValue);
                largeIntLiteral.analyze(null);
                break;
            case FLOAT:
                FloatLiteral floatLiteral = new FloatLiteral(defaultValue);
                if (floatLiteral.getType() == Type.DOUBLE) {
                    throw new AnalysisException("Default value will loose precision: " + defaultValue);
                }
            case DOUBLE:
                FloatLiteral doubleLiteral = new FloatLiteral(defaultValue);
                break;
            case DECIMAL:
                DecimalLiteral decimalLiteral = new DecimalLiteral(defaultValue);
                decimalLiteral.checkPrecisionAndScale(scalarType.getScalarPrecision(), scalarType.getScalarScale());
                break;
            case DATE:
            case DATETIME:
                DateLiteral dateLiteral = new DateLiteral(defaultValue, type);
                break;
            case CHAR:
            case VARCHAR:
            case HLL:
                if (defaultValue.length() > scalarType.getLength()) {
                    throw new AnalysisException("Default value is too long: " + defaultValue);
                }
                break;
            default:
                throw new AnalysisException("Unsupported type: " + type);
        }
    }

    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("`").append(name).append("` ");
        sb.append(typeDef.toSql()).append(" ");

        if (aggregateType != null) {
            sb.append(aggregateType.name()).append(" ");
        }

        if (!isAllowNull) {
            sb.append("NOT NULL ");
        }

        if (defaultValue != null) {
            sb.append("DEFAULT \"").append(defaultValue).append("\" ");
        }
        sb.append("COMMENT \"").append(comment).append("\"");

        return sb.toString();
    }

    public Column toColumn() {
        return new Column(name, typeDef.getType(), isKey, aggregateType, isAllowNull, defaultValue, comment);
    }

    @Override
    public String toString() { return toSql(); }
}