package com.chicm.cmraft;

import org.apache.log4j.Level;
import org.junit.Test;

import com.chicm.cmraft.core.LocalCluster;
import com.chicm.cmraft.core.RaftNode;

public class TestConnectionManager {
  
  public static void main(String[] args) throws Exception {
    TestConnectionManager t = new TestConnectionManager();
    t.testGetConnection();
  }

  @Test
  public void testGetConnection() throws Exception {
    org.apache.log4j.LogManager.getRootLogger().setLevel(Level.WARN);
    LocalCluster cluster = LocalCluster.create(5, 12888);
    RaftNode[] nodes = cluster.getNodes(); 
    
    Thread.sleep(10000);
    
    cluster.checkNodesState();
    
    Connection conn = ConnectionManager.getConnection(cluster.getConf(0));
    conn.set("key1", "value1");
    conn.set("key2", "value2");
    conn.set("key3", "value3");
    
    Result r = conn.list("");
    for(byte[] b: r.keySet()) {
      System.out.println(new String(b));
    }
    
    cluster.killLeader();
    
    Thread.sleep(10000);
    cluster.checkNodesState();
    
    Connection conn2 = ConnectionManager.getConnection(cluster.getConf(0));
    Result r2 = conn.list("");
    for(byte[] b: r2.keySet()) {
      System.out.println(new String(b));
    }
    cluster.checkNodesState();
  }
}
