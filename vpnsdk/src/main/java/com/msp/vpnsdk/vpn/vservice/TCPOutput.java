/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.msp.vpnsdk.vpn.vservice;

import android.util.Log;

import com.msp.vpnsdk.bean.Packet;
import com.msp.vpnsdk.bean.Packet.*;
import com.msp.vpnsdk.vpn.vservice.TCB.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public class TCPOutput implements Runnable
{
    private static final String TAG = TCPOutput.class.getSimpleName();

    private VhostsService vpnService;

    //deviceToNetworkTCPQueue
    private ConcurrentLinkedQueue<Packet> inputQueue;

    //networkToDeviceQueue
    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;
    private ReentrantLock tcpSelectorLock;

    boolean bOrderlyProcessACK = false;

    private Random random = new Random();
    public TCPOutput(ConcurrentLinkedQueue<Packet> inputQueue, ConcurrentLinkedQueue<ByteBuffer> outputQueue,
                     Selector selector,ReentrantLock tcpSelectorLock, VhostsService vpnService)
    {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
        this.tcpSelectorLock=tcpSelectorLock;
    }

    @Override
    public void run()
    {
        Log.i(TAG,"Started");
        try {
            while (!Thread.interrupted()) {
                boolean releaseBuffer = true;

                Packet currentPacket = inputQueue.poll();
                if (currentPacket == null){
                    Thread.sleep(11);
                    continue;
                }

                ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                currentPacket.backingBuffer = null;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();

                InetAddress destinationAddress = currentPacket.ipHeader.destinationAddress;

                TCPHeader tcpHeader = currentPacket.tcpHeader;
                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" +
                        destinationPort + ":" + sourcePort;
                TCB tcb = TCB.getTCB(ipAndPort);
                if (tcb == null){
                    //第一次握手, 客户端发送的握手包
                    Log.i(TAG,"initializeConnection:"+ipAndPort+" syn:"+tcpHeader.isSYN());
                    initializeConnection(ipAndPort, destinationAddress, destinationPort,
                            currentPacket, tcpHeader, responseBuffer);
                }
                else if (tcpHeader.isSYN()){
                    //第一次握手, 应该到tcb == null, 这里又来一次, 异常关闭
                    Log.i(TAG,"isSYN, 异常关闭, "+tcb.ipAndPort);
                    processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                }
                else if (tcpHeader.isRST()){
                    //RST表示复位，用来异常的关闭连接
                    Log.i(TAG,"isRST, 异常关闭, "+tcb.ipAndPort);
                    closeCleanly(tcb, responseBuffer);
                }
                else if (tcpHeader.isFIN()){
                    //结束一个TCP回话，但对应端口仍处于开放状态，准备接收后续数据

                    processFIN(tcb, tcpHeader, responseBuffer);
                }
                else if (tcpHeader.isACK()){
                    //第三次握手, 客户端发送的包, 可能携带有效数据; 第二次握手有服务器向客户端发送SYN +ACK
                    //向真实服务器发送数据
//                        Log.i(TAG,"isACK, "+tcb.ipAndPort);
//                        MsmLog.print("myvpn --- tcpHeader.isACK " + tcb.ipAndPort);
                    if (bOrderlyProcessACK) {
                        //有序处理, 自己释放responseBuffer, payloadBuffer
//                            releaseBuffer = false;
//                            currentPacket.backingBuffer = payloadBuffer;
//                            orderlyProcessACK(tcb, currentPacket);
                    } else {
                        if (tcpHeader.sequenceNumber < tcb.myAcknowledgementNum) {
                            // 重报不转发，丢弃
//                                MsmLog.print("myvpn -- 重报 -- sequenceNumber < myAcknowledgementNum, tcb.getPacketMapSize() : " + tcb.getPacketMapSize()
//                                        + ", sequenceNumber : " + tcpHeader.sequenceNumber + ", myAcknowledgementNum : " + tcb.myAcknowledgementNum
//                                        + ", destinationPort : " + tcpHeader.destinationPort);
                        } else {
                            processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);
                        }
                    }
                }

                if (responseBuffer.position() == 0)
                    ByteBufferPool.release(responseBuffer);

                if(releaseBuffer){
                    // XXX: cleanup later;
                    ByteBufferPool.release(payloadBuffer);
                }
            }
        } catch (Exception e) {
//            MsmLog.print(e);
        } finally {
            TCB.closeAll();
            Log.i(TAG,"TCPOutput 退出");
        }
    }

    private void orderlyProcessACK(TCB tcb, Packet packet) throws IOException{
        //先缓存到有序队列
        tcb.cachePackage(packet);

        //循环发送之前缓存的队列
        while(true){
            Packet cachePacket = tcb.getPackage();
            if(cachePacket == null){
                //缓存处理完毕
                break;
            }

            //与tcb.myAcknowledgementNum期望的seq一致, 发送
            if(cachePacket.tcpHeader.sequenceNumber == tcb.myAcknowledgementNum){
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                processACK(tcb, cachePacket.tcpHeader, cachePacket.backingBuffer, responseBuffer);

                // XXX: cleanup later
                if (responseBuffer.position() == 0)
                    ByteBufferPool.release(responseBuffer);
                ByteBufferPool.release(cachePacket.backingBuffer);

            }else if(cachePacket.tcpHeader.sequenceNumber < tcb.myAcknowledgementNum){
                //小于期望, 重传了
                //回报通知重传, 释放
//                if (responseBuffer.position() == 0)
//                    ByteBufferPool.release(responseBuffer);
//                ByteBufferPool.release(cachePacket.backingBuffer);

//                if (cachePacket.tcpHeader.destinationPort == 5000) {
//                    MsmLog.print("myvpn -- 重报 -- sequenceNumber < myAcknowledgementNum, tcb.getPacketMapSize() : " + tcb.getPacketMapSize()
//                            + ", sequenceNumber : " + cachePacket.tcpHeader.sequenceNumber + ", myAcknowledgementNum : " + tcb.myAcknowledgementNum
//                            + ", destinationPort : " + cachePacket.tcpHeader.destinationPort);
//                }


            }else if(cachePacket.tcpHeader.sequenceNumber > tcb.myAcknowledgementNum){
                //大于期望, 传早了, 先缓存起来, 等一个小点的seq过来
                tcb.cachePackage(packet);

//                if (cachePacket.tcpHeader.destinationPort == 5000) {
//                    MsmLog.print("myvpn -- sequenceNumber > myAcknowledgementNum, tcb.getPacketMapSize() : " + tcb.getPacketMapSize()
//                            + ", sequenceNumber : " + cachePacket.tcpHeader.sequenceNumber + ", myAcknowledgementNum : " + tcb.myAcknowledgementNum
//                            + ", destinationPort: " + cachePacket.tcpHeader.destinationPort);
//                }

                break;
            }

        }
    }

    private void initializeConnection(String ipAndPort, InetAddress destinationAddress, int destinationPort,
                                      Packet currentPacket, TCPHeader tcpHeader, ByteBuffer responseBuffer)
            throws IOException
    {
        currentPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN())
        {
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            //add by wzl
            outputChannel.socket().setSoTimeout(1000);
            //end

            //调用 VpnService.protect() 以将应用的隧道套接字保留在系统 VPN 外部，并避免发生循环连接。
            //调用之后这个socket的读写操作将不走VPN
            vpnService.protect(outputChannel.socket());

            TCB tcb = new TCB(ipAndPort, random.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, tcpHeader.window, outputChannel, currentPacket);
            TCB.putTCB(ipAndPort, tcb);

            try
            {
                outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                tcb.haveInsertedHeader = true;

                if (outputChannel.finishConnect())
                {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    // TODO: Set MSS for receiving larger packets from the device
                    currentPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    tcb.mySequenceNum++; // SYN counts as a byte
                }
                else
                {
                    //Log.i(TAG,"connect unfinish:" + ipAndPort);

                    tcb.status = TCBStatus.SYN_SENT;
                    tcpSelectorLock.lock();
                    /*
                    当B线程阻塞在select()或select(long)方法上时，A线程调用wakeup后，B线程会立刻返回。
                    如果没有线程阻塞在select()方法上，那么下一次某个线程调用select()或select(long)方法时，会立刻返回。
                     */
                    selector.wakeup();
                    /*
                    java.nio.channels.SocketChannel.register 方法是 Java NIO 中的一个方法，它的作用是向给定的 Selector 注册该 SocketChannel，以便在其可操作时被通知。

                    当连接尚未完成时，通过调用 register 方法可以将该 SocketChannel 注册到 Selector 上，并使用 SelectionKey.OP_CONNECT 标志。然后，在连接完成后，该 SocketChannel 可以被通知。这允许程序在后台完成连接操作，而不阻塞主线程。

                    在上面的代码中，如果连接没有完成，则使用 register 方法将该 SocketChannel 注册到 Selector 上，以便在连接完成后通知该 SocketChannel。
                     */
                    tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_CONNECT, tcb);
                    tcpSelectorLock.unlock();
                    return;
                }
            }
            catch (IOException e)
            {
                Log.i(TAG,"Connection error: " + ipAndPort, e);
                currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        }
        else
        {
            currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
        }
        outputQueue.offer(responseBuffer);
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            if (tcb.status == TCBStatus.SYN_SENT)
            {
                //delete by wzl
                //tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            tcb.status = TCBStatus.LAST_ACK;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            tcb.mySequenceNum++; // FIN counts as a byte

//            if (tcb.waitingForNetworkData)
//            {
//                tcb.status = TCBStatus.CLOSE_WAIT;
//                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK,
//                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
//            }
//            else
//            {
//                tcb.status = TCBStatus.LAST_ACK;
//                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
//                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
//                tcb.mySequenceNum++; // FIN counts as a byte
//            }
        }
        outputQueue.offer(responseBuffer);
    }

    int countWrite = 0;
    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException
    {
        boolean bTcpHandshakeAck = false;
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();
//        MsmLog.print("myvpn processACK payloadSize:" +  payloadSize + ", seq : " + tcpHeader.sequenceNumber);

        synchronized (tcb)
        {
            tcb.window = tcpHeader.window;

            SocketChannel outputChannel = tcb.channel;
            if (tcb.status == TCBStatus.SYN_RECEIVED)
            {
                bTcpHandshakeAck = true;
                tcb.status = TCBStatus.ESTABLISHED;
                tcpSelectorLock.lock();
                selector.wakeup();
                tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_READ, tcb);
                tcpSelectorLock.unlock();
                tcb.waitingForNetworkData = true;
            }
            else if (tcb.status == TCBStatus.LAST_ACK)
            {
                closeCleanly(tcb, responseBuffer);
                return;
            }

            if (payloadSize == 0)  {
                if (!bTcpHandshakeAck) {
                    return; // Empty ACK, ignore
                }

                // 防止服务端主动先发消息（ftp 协议），所以保证tcp握手后，targethost一定已经发送给服务端
            }

            if (!tcb.waitingForNetworkData)
            {
                selector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            // Forward to remote server
            try
            {
                while (payloadBuffer.hasRemaining()){
                    //向服务器发送的是TCP的内容,此时payloadBuffer仍然是一个ip包, 但position指向了TCP的内容
                    if (payloadBuffer.position() == 0) {
                        Log.i(TAG,"myvpn --- result write, limit =========== 0 :" +  payloadBuffer.limit() + ", position : " + payloadBuffer.position() + ", sequenceNumber :"+ tcpHeader.sequenceNumber);
                    }

//                    if (tcpHeader.destinationPort == 5000) {
//                        MsmLog.print("myvpn --- result write, limit :" +  payloadBuffer.limit() + ", position : " + payloadBuffer.position()
//                                + ", sequenceNumber :"+ tcpHeader.sequenceNumber
//                                + ", sourcePort: " + tcpHeader.sourcePort
//                                + ", destinationPort: " + tcpHeader.destinationPort);
//                    }
//
//                    if (tcb.referencePacket.tcpHeader.sourcePort == 5000) {
//                        int len = payloadBuffer.limit() - payloadBuffer.position();
//                        countWrite += len;
//                        MsmLog.print("myvpn --- result write, processInput 长度：" +  len + ", countWrite : " + countWrite);
//                    }

//                    MsmLog.print("myvpn --- result write, sequenceNumber :"+ tcpHeader.sequenceNumber);
                    outputChannel.write(payloadBuffer);
                }
            }
            catch (IOException e)
            {
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            Packet referencePacket = tcb.referencePacket;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        }

        //我拿到客户端发送的数据通过outputChannel.write发给服务器, 为什么还有下面的操作?
        //这里的作用是假装自己是服务器, 回复客户端我受到的xxx长的数据包, 不然客户端永远不知道客户端收到包了
        outputQueue.offer(responseBuffer);
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer)
    {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) (TCPHeader.RST |  TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum + prevPayloadSize, 0);

        try {
            Packet re = null;
            buffer.position(0);
            re = new Packet(buffer);
            buffer.position(tcb.referencePacket.IP_TRAN_SIZE);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }

        outputQueue.offer(buffer);
        TCB.closeTCB(tcb);
    }

    /*
    add by wzl
     */
    private void sendFIN(TCB tcb, ByteBuffer buffer){
        tcb.status = TCB.TCBStatus.LAST_ACK;
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) (TCPHeader.FIN |  TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        tcb.mySequenceNum++; // FIN counts as a byte

        try {
            Packet re = null;
            buffer.position(0);
            re = new Packet(buffer);
            Log.i(TAG,"sendRST: " + re.ipHeader.destinationAddress.getHostAddress()+":"+re.tcpHeader.destinationPort);
            buffer.position(tcb.referencePacket.IP_TRAN_SIZE);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }

        outputQueue.offer(buffer);
    }

    private void closeCleanly(TCB tcb, ByteBuffer buffer)
    {
        ByteBufferPool.release(buffer);
        TCB.closeTCB(tcb);
    }
}
