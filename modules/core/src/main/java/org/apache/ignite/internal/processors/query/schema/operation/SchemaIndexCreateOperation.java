/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.schema.operation;

import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.internal.S;

import java.util.UUID;

/**
 * Schema index create operation.
 */
public class SchemaIndexCreateOperation extends SchemaIndexAbstractOperation {
    /** */
    private static final long serialVersionUID = 0L;

    /** Table name. */
    private final String tblName;

    /** Index. */
    @GridToStringInclude
    private final QueryIndex idx;

    /** Ignore operation if index exists. */
    private final boolean ifNotExists;

    /**
     * Constructor.
     *
     * @param opId Operation id.
     * @param space Space.
     * @param tblName Table name.
     * @param idx Index params.
     * @param ifNotExists Ignore operation if index exists.
     */
    public SchemaIndexCreateOperation(UUID opId, String space, String tblName, QueryIndex idx, boolean ifNotExists) {
        super(opId, space);

        this.tblName = tblName;
        this.idx = idx;
        this.ifNotExists = ifNotExists;
    }

    /** {@inheritDoc} */
    @Override public String indexName() {
        return QueryUtils.indexName(tblName, idx);
    }

    /**
     * @return Table name.
     */
    public String tableName() {
        return tblName;
    }

    /**
     * @return Index params.
     */
    public QueryIndex index() {
        return idx;
    }

    /**
     * @return Ignore operation if index exists.
     */
    public boolean ifNotExists() {
        return ifNotExists;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SchemaIndexCreateOperation.class, this, "parent", super.toString());
    }
}
