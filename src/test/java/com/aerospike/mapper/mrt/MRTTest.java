package com.aerospike.mapper.mrt;

import com.aerospike.client.*;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.Test;

public class MRTTest {

    @Test
    public void testMRT() {
        AerospikeClient client = new AerospikeClient("localhost", 3000);
        String binName = "bin";
        final Key key = new Key("test", "test", "testKey2");
        client.put(null, key, new Bin(binName, "val1"));
        Txn txn = new Txn();
        WritePolicy wp = client.copyWritePolicyDefault();
        wp.txn = txn;
        client.put(wp, key, new Bin(binName, "val2"));
        try {
            // This write should be blocked.
            client.put(null, key, new Bin(binName, "val3"));
            throw new AerospikeException("Unexpected success");
        } catch (AerospikeException e) {
            if (e.getResultCode() != ResultCode.MRT_BLOCKED) {
                throw e;
            }
        }
        client.commit(txn);
    }


}
