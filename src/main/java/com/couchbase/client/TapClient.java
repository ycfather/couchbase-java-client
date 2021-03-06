/**
 * Copyright (C) 2009-2013 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.client;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.naming.ConfigurationException;

import net.spy.memcached.BroadcastOpFactory;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationCallback;
import net.spy.memcached.ops.OperationStatus;
import net.spy.memcached.ops.TapOperation;
import net.spy.memcached.tapmessage.RequestMessage;
import net.spy.memcached.tapmessage.ResponseMessage;
import net.spy.memcached.tapmessage.TapAck;
import net.spy.memcached.tapmessage.TapOpcode;
import net.spy.memcached.tapmessage.TapStream;

/**
 * A tap client for Couchbase server.
 */
public class TapClient extends net.spy.memcached.TapClient {
  private List<URI> baseList;
  private String bucketName;
  private String pwd;

  /**
   * Creates a cluster aware tap client for Couchbase Server.
   *
   * This type of TapClient will TAP all servers in the specified cluster and
   * will react to changes in the number of cluster nodes.
   *
   * @param baseList a list of servers to get the cluster configuration from.
   * @param bucketName the name of the bucket to tap.
   * @param pwd the buckets password.
   */
  public TapClient(final List<URI> baseList, final String bucketName,
      final String pwd) {
    for (URI bu : baseList) {
      if (!bu.isAbsolute()) {
        throw new IllegalArgumentException("The base URI must be absolute");
      }
    }
    this.baseList = baseList;
    this.bucketName = bucketName;
    this.pwd = pwd;
  }

  /**
   * Gets the next tap message from the queue of received tap messages.
   *
   * @return The tap message at the head of the queue or null if the queue is
   *         empty for more than one second.
   */
  public ResponseMessage getNextMessage() {
    return getNextMessage(1, TimeUnit.SECONDS);
  }

  /**
   * Gets the next tap message from the queue of received tap messages.
   *
   * @param time the amount of time to wait for a message.
   * @param timeunit the unit of time to use.
   * @return The tap message at the head of the queue or null if the queue is
   *         empty for the given amount of time.
   */
  public ResponseMessage getNextMessage(long time, TimeUnit timeunit) {
    try {
      Object m = rqueue.poll(time, timeunit);
      if (m == null) {
        return null;
      } else if (m instanceof ResponseMessage) {
        return (ResponseMessage) m;
      } else if (m instanceof TapAck) {
        TapAck ack = (TapAck) m;
        tapAck((com.couchbase.client.TapConnectionProvider)ack.getConn(),
            ack.getNode(), ack.getOpcode(), ack.getOpaque(), ack.getCallback());
        return null;
      } else {
        throw new RuntimeException("Unexpected tap message type");
      }
    } catch (InterruptedException e) {
      shutdown();
      return null;
    }
  }

  /**
   * Decides whether the client has received tap messages or will receive more
   * messages in the future.
   *
   * @return true if the client has tap responses or expects to have responses
   *         in the future. False otherwise.
   */
  public boolean hasMoreMessages() {
    if (!rqueue.isEmpty()) {
      return true;
    } else {
      synchronized (omap) {
        Iterator<TapStream> itr = omap.keySet().iterator();
        while (itr.hasNext()) {
          TapStream op = itr.next();
          if (op.isCompleted() || op.isCancelled()
              || op.hasErrored()) {
            omap.get(op).shutdown();
            omap.remove(op);
          }
        }
        if (omap.size() > 0) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Allows the user to specify a custom tap message.
   *
   * This API for TAP is still evolving, and only recommended for advanced
   * usage.
   *
   * See http://www.couchbase.com/wiki/display/couchbase/TAP+Protocol
   *
   * @param id the named tap id that can be used to resume a disconnected tap
   *          stream
   * @param message the custom tap message that will be used to initiate the tap
   *          stream.
   * @return the operation that controls the tap stream.
   * @throws ConfigurationException a bad configuration was received from the
   *           Couchbase cluster.
   * @throws IOException if there are errors connecting to the cluster.
   */
  public TapStream tapCustom(final String id, final RequestMessage message)
    throws ConfigurationException, IOException {
    final TapConnectionProvider conn = new TapConnectionProvider(baseList,
        bucketName, pwd);
    final TapStream ts = new TapStream();
    conn.broadcastOp(new BroadcastOpFactory() {
      public Operation newOp(final MemcachedNode n,
          final CountDownLatch latch) {
        Operation op =  conn.getOpFactory().tapCustom(id, message,
            new TapOperation.Callback() {
            public void receivedStatus(OperationStatus status) {
            }
            public void gotData(ResponseMessage tapMessage) {
              rqueue.add(tapMessage);
              messagesRead++;
            }
            public void gotAck(MemcachedNode node, TapOpcode opcode,
                int opaque) {
              rqueue.add(new TapAck(conn, node, opcode, opaque, this));
            }
            public void complete() {
              latch.countDown();
            }
          });
        ts.addOp((TapOperation)op);
        return op;
      }
    });
    synchronized (omap) {
      omap.put(ts, conn);
    }
    return ts;
  }

  /**
   * Specifies a tap stream that will send all key-value mutations that take
   * place in the future.
   *
   * @param id the named tap id that can be used to resume a disconnected tap
   *          stream
   * @param runTime the amount of time to do backfill for. Set to 0 for infinite
   *          backfill.
   * @param timeunit the unit of time for the runtime parameter.
   * @return the operation that controls the tap stream.
   * @throws ConfigurationException a bad configuration was received from the
   *           Couchbase cluster.
   * @throws IOException If there are errors connecting to the cluster.
   */
  public TapStream tapBackfill(String id, final int runTime,
      final TimeUnit timeunit) throws IOException, ConfigurationException {
    return tapBackfill(id, -1, runTime, timeunit);
  }

  /**
   * Specifies a tap stream that will send all key-value mutations that took
   * place after a specific date.
   *
   * @param id the named tap id that can be used to resume a disconnected tap
   *          stream
   * @param date the date to begin sending key mutations from. Specify -1 to
   *          send all future key-value mutations.
   * @param runTime the amount of time to do backfill for. Set to 0 for infinite
   *          backfill.
   * @param timeunit the unit of time for the runtime parameter.
   * @return the operation that controls the tap stream.
   * @throws ConfigurationException a bad configuration was received from the
   *           Couchbase cluster.
   * @throws IOException If there are errors connecting to the cluster.
   */
  public TapStream tapBackfill(final String id, final long date,
      final int runTime, final TimeUnit timeunit) throws IOException,
      ConfigurationException {
    final TapConnectionProvider conn = new TapConnectionProvider(baseList,
        bucketName, pwd);
    final TapStream ts = new TapStream();
    conn.broadcastOp(new BroadcastOpFactory() {
      public Operation newOp(final MemcachedNode n,
          final CountDownLatch latch) {
        Operation op =  conn.getOpFactory().tapBackfill(id, date,
              new TapOperation.Callback() {
            public void receivedStatus(OperationStatus status) {
            }
            public void gotData(ResponseMessage tapMessage) {
              if (!conn.isPrimaryForKey(n, tapMessage.getKey())) {
                return;
              }
              rqueue.add(tapMessage);
              messagesRead++;
            }
            public void gotAck(MemcachedNode node, TapOpcode opcode,
                int opaque) {
              rqueue.add(new TapAck(conn, node, opcode, opaque, this));
            }
            public void complete() {
              latch.countDown();
            }
          });
        ts.addOp((TapOperation)op);
        return op;
      }
    });
    synchronized (omap) {
      omap.put(ts, conn);
    }

    if (runTime > 0) {
      Runnable r = new Runnable() {
        @Override
        public void run() {
          try {
            Thread.sleep(TimeUnit.MILLISECONDS.convert(runTime, timeunit));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          conn.shutdown();
          synchronized (omap) {
            omap.remove(ts);
          }
        }
      };
      new Thread(r).start();
    }
    return ts;
  }

  /**
   * Specifies a tap stream that will take a snapshot of items in Couchbase and
   * send them through a tap stream.
   *
   * @param id the named tap id that can be used to resume a disconnected tap
   *          stream
   * @return the operation that controls the tap stream.
   * @throws ConfigurationException a bad configuration was received from the
   *           Couchbase cluster.
   * @throws IOException If there are errors connecting to the cluster.
   */
  public TapStream tapDump(final String id) throws IOException,
      ConfigurationException {
    final TapConnectionProvider conn = new TapConnectionProvider(baseList,
        bucketName, pwd);
    final TapStream ts = new TapStream();
    conn.broadcastOp(new BroadcastOpFactory() {
      public Operation newOp(final MemcachedNode n,
          final CountDownLatch latch) {
        Operation op =  conn.getOpFactory().tapDump(id,
              new TapOperation.Callback() {
            public void receivedStatus(OperationStatus status) {
            }
            public void gotData(ResponseMessage tapMessage) {
              rqueue.add(tapMessage);
              messagesRead++;
            }
            public void gotAck(MemcachedNode node, TapOpcode opcode,
                int opaque) {
              rqueue.add(new TapAck(conn, node, opcode, opaque, this));
            }
            public void complete() {
              latch.countDown();
            }
          });
        ts.addOp((TapOperation)op);
        return op;
      }
    });
    synchronized (omap) {
      omap.put(ts, conn);
    }
    return ts;
  }

  private void tapAck(TapConnectionProvider conn, MemcachedNode node,
      TapOpcode opcode, int opaque, OperationCallback cb) {
    final Operation op = conn.getOpFactory().tapAck(opcode, opaque, cb);
    conn.addTapAckOp(node, op);
  }

  /**
   * Shuts down all tap streams that are currently running.
   */
  public void shutdown() {
    synchronized (omap) {
      for (Map.Entry<TapStream, net.spy.memcached.TapConnectionProvider> me
          : omap.entrySet()) {
        me.getValue().shutdown();
      }
    }
  }

  /**
   * The number of messages read by all of the tap streams created with this
   * client. This will include a count of all tap response types.
   *
   * @return The number of messages read
   */
  public long getMessagesRead() {
    return messagesRead;
  }
}

