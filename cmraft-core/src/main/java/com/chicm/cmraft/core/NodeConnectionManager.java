/**
* Copyright 2014 The CmRaft Project
*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at:
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/


package com.chicm.cmraft.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.chicm.cmraft.common.Configuration;
import com.chicm.cmraft.common.ServerInfo;
import com.chicm.cmraft.log.RaftLog;
import com.chicm.cmraft.protobuf.generated.RaftProtos.AppendEntriesResponse;
import com.chicm.cmraft.protobuf.generated.RaftProtos.CollectVoteResponse;
import com.chicm.cmraft.protobuf.generated.RaftProtos.RaftLogEntry;
import com.google.common.base.Preconditions;
import com.google.protobuf.ServiceException;

public class NodeConnectionManager {
  static final Log LOG = LogFactory.getLog(NodeConnectionManager.class);
  private Configuration conf;
  private Map<ServerInfo, NodeConnection> connections;
  private RaftNode raftNode;
  
  public NodeConnectionManager(Configuration conf, RaftNode node) {
    this.conf = conf;
    this.raftNode = node;
    initServerList();
  }
  
  private void initServerList() {
    connections = new ConcurrentHashMap<>();
    
    for (ServerInfo remote : getRaftNode().getRemoteServers()) {
      NodeConnection client = new DefaultNodeConnection(conf, remote);
      connections.put(remote, client);
    }
  }
  
  public RaftNode getRaftNode() {
    return raftNode;
  }
  
  public Set<ServerInfo> getRemoteServers() {
    return connections.keySet();
  }
  
  public void beatHeart(long term, ServerInfo leaderId, long leaderCommit,
      long prevLogIndex, long prevLogTerm) {
    appendEntries(term, leaderId, leaderCommit, prevLogIndex, prevLogTerm, 
      new ArrayList<RaftLogEntry>(), 0);
    
  }
  
  public void appendEntries(RaftLog logMgr, long lastApplied) {
    int nServers = getRemoteServers().size();
    if(nServers <= 0) {
      return;
    }
    
    ExecutorService executor = Executors.newFixedThreadPool(nServers,
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
          Thread t = new Thread(r);
          t.setName(getRaftNode().getName() + "-AsyncRpcCaller" + (byte)System.currentTimeMillis());
          return t;
        }
    });
    
    for(ServerInfo server: getRemoteServers()) {
      NodeConnection conn = connections.get(server);
      long startIndex = logMgr.getFollowerMatchIndex(server) + 1;
          
      LOG.info(getRaftNode().getName() + ": SENDING appendEntries Request TO: " + server);
      Thread t = new Thread(new AsynchronousAppendEntriesWorker(getRaftNode(), conn, getRaftNode().getRaftLog(), 
        getRaftNode().getServerInfo(), getRaftNode().getCurrentTerm(), logMgr.getCommitIndex(), startIndex-1, 
        logMgr.getLogTerm(startIndex-1), logMgr.getLogEntries(startIndex, lastApplied), lastApplied));
      t.setDaemon(true);
      executor.execute(t);
    }
  }
  
  private void appendEntries(long term, ServerInfo leaderId, long leaderCommit,
      long prevLogIndex, long prevLogTerm, List<RaftLogEntry> entries, long maxIndex) {
    Preconditions.checkNotNull(entries);
    
    int nServers = getRemoteServers().size();
    if(nServers <= 0) {
      return;
    }
    
    ExecutorService executor = Executors.newFixedThreadPool(nServers,
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
          Thread t = new Thread(r);
          t.setName(getRaftNode().getName() + "-AsyncRpcCaller" + (byte)System.currentTimeMillis());
          return t;
        }
    });
    
    for(ServerInfo server: getRemoteServers()) {
      NodeConnection connection = connections.get(server);
      LOG.debug(getRaftNode().getName() + ": SENDING appendEntries Request TO: " + server);
      Thread t = new Thread(new AsynchronousAppendEntriesWorker(getRaftNode(), connection, getRaftNode().getRaftLog(), getRaftNode().getServerInfo(), term,
        leaderCommit, prevLogIndex, prevLogTerm, entries, maxIndex));
      t.setDaemon(true);
      executor.execute(t);
    }
  }
  
  public void collectVote(long term, long lastLogIndex, long lastLogTerm) {
    int nServers = getRemoteServers().size();
    if(nServers <= 0) {
      return;
    }
    ExecutorService executor = Executors.newFixedThreadPool(nServers,
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
          Thread t = new Thread(r);
          t.setName(getRaftNode().getName() + "-AsyncRpcCaller" + (byte)System.currentTimeMillis());
          return t;
        }
    });
    
    for(ServerInfo server: getRemoteServers()) {
      NodeConnection conn = connections.get(server);
      LOG.debug(getRaftNode().getName() + ": SENDING COLLECTVOTE Request TO: " + server);
      Thread t = new Thread(new AsynchronousVoteWorker(getRaftNode(), conn, getRaftNode().getServerInfo(), term,
        lastLogIndex, lastLogTerm));
      t.setDaemon(true);
      executor.execute(t);
    }
  }
  
  class AsynchronousVoteWorker implements Runnable {
    private ServerInfo candidate;
    private long term;
    private long lastLogIndex;
    private long lastLogTerm;
    private RaftNode node;
    private NodeConnection connection;
    
    public AsynchronousVoteWorker(RaftNode node, NodeConnection connnection, ServerInfo thisServer, long term,
        long lastLogIndex, long lastLogTerm) {
      this.connection = connnection;
      this.node = node;
      this.candidate = thisServer;
      this.term = term;
      this.lastLogIndex = lastLogIndex;
      this.lastLogTerm = lastLogTerm;
    }
    
    @Override
    public void run () {
      CollectVoteResponse response = null;
      try {
        response = connection.collectVote(candidate, term, lastLogIndex, lastLogTerm);
        if(response != null && response.getGranted()) {
          node.voteReceived(ServerInfo.copyFrom(response.getFromHost()), response.getTerm());
        } else if( response == null) {
          LOG.error("RPC failed, response == null");
        } else if(response.getGranted() == false) {
          LOG.info(node.getName() + "VOTE REJECTED BY " + response.getFromHost().getHostName()
            + ":" + response.getFromHost().getPort());
        }
      } catch(Exception e) {
        LOG.error("RPC: collectVote failed: from " + getRaftNode().getName() + 
          ", to: " + connection.getRemoteServer() + ": " + e.getMessage());
      }
    }
  }

  class AsynchronousAppendEntriesWorker implements Runnable {
    private ServerInfo leader;
    private long term;
    private long leaderCommit;
    private long prevLogIndex;
    private long prevLogTerm;
    private List<RaftLogEntry> entries;
    private long maxIndex;
    private RaftNode node;
    private NodeConnection connection;
    private RaftLog logManager;
    
    public AsynchronousAppendEntriesWorker(RaftNode node, NodeConnection connection, RaftLog logMgr, ServerInfo thisServer, long term,
        long leaderCommit, long prevLogIndex, long prevLogTerm, List<RaftLogEntry> entries, Long maxIndex) {
      this.connection = connection;
      this.node = node;
      this.logManager = logMgr;
      this.leader = thisServer;
      this.term = term;
      this.leaderCommit = leaderCommit;
      this.prevLogIndex = prevLogIndex;
      this.prevLogTerm = prevLogTerm;
      this.entries = entries;
      this.maxIndex = maxIndex;
    }
    
    @Override
    public void run () {
      try {
        AppendEntriesResponse response = connection.appendEntries(term, leader, leaderCommit, prevLogIndex, prevLogTerm, entries);
        
        if(response != null && entries != null && logManager != null) {
          logManager.onAppendEntriesResponse(connection.getRemoteServer(), response.getTerm(),
            response.getSuccess(), maxIndex); 
        }
      } catch(Exception e) {
        LOG.error("RPC: collectVote failed: from " + getRaftNode().getName() + 
          ", to: " + connection.getRemoteServer(), e);
      }
    }
  }
}
