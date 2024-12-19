package com.aerospike.mapper.tools;

import com.aerospike.client.Txn;

public class MapperTx {
    private final Txn tx;
    private final AeroMapper mapper;

    public MapperTx(AeroMapper mapper) {
        this.tx = new Txn();
        this.mapper = mapper;
    }




}
