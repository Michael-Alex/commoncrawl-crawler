/**
 * Copyright 2008 - CommonCrawl Foundation
 * 
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 **/
package org.commoncrawl.io;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Options;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.Section;
import org.xbill.DNS.TCPClient;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;
import org.xbill.DNS.UDPClient;
import org.xbill.DNS.WireParseException;
import org.xbill.DNS.ZoneTransferException;
import org.xbill.DNS.ZoneTransferIn;

/**
 * An implementation of Resolver that sends one query to one server.
 * SimpleResolver handles TCP retries, transaction security (TSIG), and EDNS 0.
 * 
 * @see Resolver
 * @see TSIG
 * @see OPTRecord
 * 
 * @author Brian Wellington
 */

public final class NIODNSSimpleResolverImpl implements Resolver {

  /** logging **/
  private static final Log LOG = LogFactory.getLog(NIODNSSimpleResolverImpl.class);

  /** The default port to send queries to */
  public static final int DEFAULT_PORT = 53;

  /** The default EDNS payload size */
  public static final int DEFAULT_EDNS_PAYLOADSIZE = 1280;

  private InetSocketAddress address;
  private InetSocketAddress localAddress;
  private boolean useTCP, ignoreTruncation;
  private OPTRecord queryOPT;
  private TSIG tsig;
  private long timeoutValue = 30 * 1000;

  private static final short DEFAULT_UDPSIZE = 512;

  private static String defaultResolver = "localhost";

  /** Sets the default host (initially localhost) to query */
  public static void setDefaultResolver(String hostname) {
    defaultResolver = hostname;
  }

  private boolean _recycleClients = true;

  private NIODNSLocalResolver _session;

  /**
   * Creates a SimpleResolver that will query the specified host
   * 
   * @exception UnknownHostException
   *              Failure occurred while finding the host
   */
  public NIODNSSimpleResolverImpl(NIODNSLocalResolver session, String hostname) throws UnknownHostException {
    _session = session;
    if (hostname == null) {
      hostname = ResolverConfig.getCurrentConfig().server();
      if (hostname == null)
        hostname = defaultResolver;
    }
    InetAddress addr;
    if (hostname.equals("0"))
      addr = InetAddress.getLocalHost();
    else
      addr = InetAddress.getByName(hostname);
    address = new InetSocketAddress(addr, DEFAULT_PORT);
  }

  private void applyEDNS(Message query) {
    if (queryOPT == null || query.getOPT() != null)
      return;
    query.addRecord(queryOPT, Section.ADDITIONAL);
  }

  public byte[] doTCPClientSendRecv(SocketAddress local, SocketAddress remote, byte[] data, long endTime,
      boolean recycleClients) throws IOException {
    int pass = 0;
    byte dataOut[] = null;

    TCPClient client = null;

    while (pass < 2) {

      // first pass try to claim a recycled socket ...
      if (recycleClients && _session != null) {
        synchronized (_session._recycledClients) {
          if (_session._recycledClients.size() != 0 && pass == 0) {
            client = _session._recycledClients.removeFirst();
            /*
             * if (Environment.detailLogEnabled())
             * LOG.debug("Using Recycled Client for DNS Resolution");
             */
          }
        }
      }
      if (client != null) {
        try {
          // LOG.info("Using Recycled Client for Thread:" +
          // Thread.currentThread().getName());

          client.setEndTime(System.currentTimeMillis() + 60000);
          client.send(data);
          dataOut = client.recv();

          // LOG.info("Recycled Client:" + Thread.currentThread().getName() +
          // "  SendRcv Returned:" + dataOut);

          if (dataOut == null) {
            throw new IOException("client.recv returned NULL");
          }
          // recycle this client ...
          if (recycleClients && _session != null) {
            synchronized (_session._recycledClients) {
              _session._recycledClients.addLast(client);
              /*
               * if (Environment.detailLogEnabled())
               * LOG.debug("Recycling DNS Client");
               */
            }
          }
          break;
        } catch (IOException e) {
          LOG.error("Recycled DNS Resolution Failed on Pass:" + pass); // with
                                                                       // Exception:"
                                                                       // +
                                                                       // StringUtils.stringifyException(e));
          client.cleanup();
          client = null;
        }
      } else {

        client = new TCPClient(endTime);

        try {
          // LOG.info("Using NEW Client for Thread:" +
          // Thread.currentThread().getName());
          client.setEndTime(System.currentTimeMillis() + 60000);
          if (local != null)
            client.bind(local);
          client.connect(remote);
          client.send(data);
          dataOut = client.recv();

          // LOG.info("NEW  Client:" + Thread.currentThread().getName() +
          // "  SendRcv Returned:" + dataOut);
          if (dataOut == null) {
            throw new IOException("client.recv returned NULL");
          }

          if (recycleClients && _session != null) {
            synchronized (_session._recycledClients) {
              _session._recycledClients.addLast(client);
              /*
               * if (Environment.detailLogEnabled())
               * LOG.debug("Recycling DNS Client");
               */
            }
          } else {
            client.cleanup();
            client = null;
          }

          break;
        } catch (IOException e) {

          LOG.error(StringUtils.stringifyException(e));
          client.cleanup();
          client = null;
          /*
           * if (Environment.detailLogEnabled())
           * LOG.debug("Recycled DNS Resolution Failed on Pass:"+ pass);
           */
        }
      }
      pass++;
    }
    if (dataOut == null) {
      LOG.error("DNS Resolution returned NULL DATA");
    }
    return dataOut;
  }

  /**
   * Creates a SimpleResolver. The host to query is either found by using
   * ResolverConfig, or the default host is used.
   * 
   * @see ResolverConfig
   * @exception UnknownHostException
   *              Failure occurred while finding the host
   */
  /*
   * public NIODNSSimpleResolverImpl() throws UnknownHostException { this(null);
   * }
   */
  InetSocketAddress getAddress() {
    return address;
  }

  long getTimeout() {
    return timeoutValue;
  }

  TSIG getTSIGKey() {
    return tsig;
  }

  private int maxUDPSize(Message query) {
    OPTRecord opt = query.getOPT();
    if (opt == null)
      return DEFAULT_UDPSIZE;
    else
      return opt.getPayloadSize();
  }

  private Message parseMessage(byte[] b) throws WireParseException {
    try {
      return (new Message(b));
    } catch (IOException e) {
      if (Options.check("verbose"))
        e.printStackTrace();
      if (!(e instanceof WireParseException))
        e = new WireParseException("Error parsing message");
      throw (WireParseException) e;
    }
  }

  /**
   * Sends a message to a single server and waits for a response. No checking is
   * done to ensure that the response is associated with the query.
   * 
   * @param query
   *          The query to send.
   * @return The response.
   * @throws IOException
   *           An error occurred while sending or receiving.
   */
  public Message send(Message query) throws IOException {
    if (Options.check("verbose"))
      System.err.println("Sending to " + address.getAddress().getHostAddress() + ":" + address.getPort());

    if (query.getHeader().getOpcode() == Opcode.QUERY) {
      Record question = query.getQuestion();
      if (question != null && question.getType() == Type.AXFR)
        return sendAXFR(query);
    }

    query = (Message) query.clone();
    applyEDNS(query);
    if (tsig != null)
      tsig.apply(query, null);

    byte[] out = query.toWire(Message.MAXLENGTH);
    int udpSize = maxUDPSize(query);
    boolean tcp = false;
    long endTime = System.currentTimeMillis() + timeoutValue;
    do {
      byte[] in;

      if (useTCP || out.length > udpSize)
        tcp = true;
      if (tcp) {
        in = doTCPClientSendRecv(localAddress, address, out, endTime, _recycleClients);
        // in = TCPClient.sendrecv(localAddress, address, out,endTime);
      } else
        in = UDPClient.sendrecv(localAddress, address, out, udpSize, endTime);

      // check for null ...
      if (in == null) {
        throw new IOException("sendRecv Returned NULL RESULT");
      }
      /*
       * Check that the response is long enough.
       */
      if (in.length < Header.LENGTH) {
        throw new WireParseException("invalid DNS header - " + "too short");
      }
      /*
       * Check that the response ID matches the query ID. We want to check this
       * before actually parsing the message, so that if there's a malformed
       * response that's not ours, it doesn't confuse us.
       */
      int id = ((in[0] & 0xFF) << 8) + (in[1] & 0xFF);
      int qid = query.getHeader().getID();
      if (id != qid) {
        String error = "invalid message id: expected " + qid + "; got id " + id;
        if (tcp) {
          throw new WireParseException(error);
        } else {
          if (Options.check("verbose")) {
            System.err.println(error);
          }
          continue;
        }
      }
      Message response = parseMessage(in);
      verifyTSIG(query, response, in, tsig);
      if (!tcp && !ignoreTruncation && response.getHeader().getFlag(Flags.TC)) {
        tcp = true;
        continue;
      }
      return response;
    } while (true);
  }

  /**
   * Asynchronously sends a message to a single server, registering a listener
   * to receive a callback on success or exception. Multiple asynchronous
   * lookups can be performed in parallel. Since the callback may be invoked
   * before the function returns, external synchronization is necessary.
   * 
   * @param query
   *          The query to send
   * @param listener
   *          The object containing the callbacks.
   * @return An identifier, which is also a parameter in the callback
   */
  public Object sendAsync(final Message query, final ResolverListener listener) {
    // TODO: IMPLEMENT THIS
    throw new RuntimeException("sendAsync NOT IMPLEMENTED");
  }

  private Message sendAXFR(Message query) throws IOException {
    Name qname = query.getQuestion().getName();
    ZoneTransferIn xfrin = ZoneTransferIn.newAXFR(qname, address, tsig);
    xfrin.setTimeout((int) (getTimeout() / 1000));
    try {
      xfrin.run();
    } catch (ZoneTransferException e) {
      throw new WireParseException(e.getMessage());
    }
    List records = xfrin.getAXFR();
    Message response = new Message(query.getHeader().getID());
    response.getHeader().setFlag(Flags.AA);
    response.getHeader().setFlag(Flags.QR);
    response.addRecord(query.getQuestion(), Section.QUESTION);
    Iterator it = records.iterator();
    while (it.hasNext())
      response.addRecord((Record) it.next(), Section.ANSWER);
    return response;
  }

  /**
   * Sets the address of the server to communicate with (on the default DNS
   * port)
   * 
   * @param addr
   *          The address of the DNS server
   */
  public void setAddress(InetAddress addr) {
    address = new InetSocketAddress(addr, address.getPort());
  }

  /**
   * Sets the address of the server to communicate with.
   * 
   * @param addr
   *          The address of the DNS server
   */
  public void setAddress(InetSocketAddress addr) {
    address = addr;
  }

  public void setEDNS(int level) {
    setEDNS(level, 0, 0, null);
  }

  public void setEDNS(int level, int payloadSize, int flags, List options) {
    if (level != 0 && level != -1)
      throw new IllegalArgumentException("invalid EDNS level - " + "must be 0 or -1");
    if (payloadSize == 0)
      payloadSize = DEFAULT_EDNS_PAYLOADSIZE;
    queryOPT = new OPTRecord(payloadSize, 0, level, flags, options);
  }

  public void setIgnoreTruncation(boolean flag) {
    this.ignoreTruncation = flag;
  }

  /**
   * Sets the local address to bind to when sending messages. A random port will
   * be used.
   * 
   * @param addr
   *          The local address to send messages from.
   */
  public void setLocalAddress(InetAddress addr) {
    localAddress = new InetSocketAddress(addr, 0);
  }

  /**
   * Sets the local address to bind to when sending messages.
   * 
   * @param addr
   *          The local address to send messages from.
   */
  public void setLocalAddress(InetSocketAddress addr) {
    localAddress = addr;
  }

  public void setPort(int port) {
    address = new InetSocketAddress(address.getAddress(), port);
  }

  public void setTCP(boolean flag) {
    this.useTCP = flag;
  }

  public void setTimeout(int secs) {
    setTimeout(secs, 0);
  }

  public void setTimeout(int secs, int msecs) {
    timeoutValue = (long) secs * 1000 + msecs;
  }

  public void setTSIGKey(TSIG key) {
    tsig = key;
  }

  private void verifyTSIG(Message query, Message response, byte[] b, TSIG tsig) {
    if (tsig == null)
      return;
    int error = tsig.verify(response, b, query.getTSIG());
    if (error == Rcode.NOERROR)
      response.tsigState = Message.TSIG_VERIFIED;
    else
      response.tsigState = Message.TSIG_FAILED;
    if (Options.check("verbose"))
      System.err.println("TSIG verify: " + Rcode.string(error));
  }

}
