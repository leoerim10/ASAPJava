package net.sharksystem.asap.protocol;

import net.sharksystem.asap.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ASAPPersistentConnection extends ASAPProtocolEngine
        implements ASAPConnection, Runnable, ThreadFinishedListener {

    private final ASAPConnectionListener asapConnectionListener;
    private final MultiASAPEngineFS multiASAPEngineFS;
    private final ThreadFinishedListener threadFinishedListener;
    private Thread managementThread = null;
    private final long maxExecutionTime;
    private String remotePeer;

    private List<ASAPOnlineMessageSource> onlineMessageSources = new ArrayList<>();
    private Thread threadWaiting4StreamsLock;
    private boolean terminated = false;

    public ASAPPersistentConnection(InputStream is, OutputStream os, MultiASAPEngineFS multiASAPEngineFS,
                                    ASAP_1_0 protocol,
                                    long maxExecutionTime, ASAPConnectionListener asapConnectionListener,
                                    ThreadFinishedListener threadFinishedListener) {

        super(is, os, protocol);

        this.multiASAPEngineFS = multiASAPEngineFS;
        this.maxExecutionTime = maxExecutionTime;
        this.asapConnectionListener = asapConnectionListener;
        this.threadFinishedListener = threadFinishedListener;
    }

    private String getLogStart() {
        return this.getClass().getSimpleName() + ": ";
    }

    private void setRemotePeer(String remotePeerName) {
        if(this.remotePeer == null) {

            this.remotePeer = remotePeerName;

            StringBuilder sb = new StringBuilder();
            sb.append(this.getLogStart());
            sb.append("set remotePeerName after reading first asap message: ");
            sb.append(remotePeerName);
            System.out.println(sb.toString());

            if(this.asapConnectionListener != null) {
                this.asapConnectionListener.asapConnectionStarted(remotePeerName, this);
            }
        }
    }

    @Override
    public CharSequence getRemotePeer() {
        return this.remotePeer;
    }

    @Override
    public void removeOnlineMessageSource(ASAPOnlineMessageSource source) {
        this.onlineMessageSources.remove(source);
    }

    public boolean isSigned() {
        return false;
    }

    @Override
    public void kill() {
        this.kill(new ASAPException("kill called from outside asap connection"));
    }

    public void kill(Exception e) {
        if(!this.terminated) {
            this.terminated = true;
            // kill reader - proofed to be useful in a bluetooth environment
            if(this.pduReader != null && this.pduReader.isAlive()) {
                this.pduReader.interrupt();
            }
            if(this.managementThread != null && this.managementThread.isAlive()) {
                this.managementThread.interrupt();
            }
            // inform listener
            if (this.asapConnectionListener != null) {
                this.asapConnectionListener.asapConnectionTerminated(e, this);
            }

            if (this.threadFinishedListener != null) {
                this.threadFinishedListener.finished(Thread.currentThread());
            }
        }
    }

    @Override
    public void finished(Thread t) {
        if(this.managementThread != null) {
            this.managementThread.interrupt();
        }
    }

    private void terminate(String message, Exception e) {
        // write log
        StringBuilder sb = this.startLog();
        sb.append(message);
        if(e != null) {
            sb.append(e.getLocalizedMessage());
        }

        sb.append(" | ");
        System.out.println(sb.toString());

        this.kill();
    }

    private void sendOnlineMessages() throws IOException {
        List<ASAPOnlineMessageSource> copy = onlineMessageSources;
        this.onlineMessageSources = new ArrayList<>();
        while(!copy.isEmpty()) {
            ASAPOnlineMessageSource asapOnline = copy.remove(0);
            StringBuilder sb = this.startLog();
            sb.append("going to send online message");
            System.out.println(sb.toString());
            asapOnline.sendMessages(this, this.os);
        }
    }

    private class OnlineMessageSenderThread extends Thread {
        public Exception caughtException = null;
        public void run() {
            try {
                // get exclusive access to streams
                System.out.println(startLog() + "online sender is going to wait for stream access");
                wait4ExclusiveStreamsAccess();
                System.out.println(startLog() + "online sender got stream access");
                sendOnlineMessages();
                // prepare a graceful death
                onlineMessageSenderThread = null;
                // are new message waiting in the meantime?
                checkRunningOnlineMessageSender();
            } catch (IOException e) {
                terminate("could not write data into stream", e);
            }
            finally {
                System.out.println(startLog() + "online sender releases lock");
                releaseStreamsLock();
            }
        }
    }

    private OnlineMessageSenderThread onlineMessageSenderThread = null;
    private ASAPPDUReader pduReader = null;
    Thread executor = null;

    @Override
    public void addOnlineMessageSource(ASAPOnlineMessageSource source) {
        this.onlineMessageSources.add(source);
        this.checkRunningOnlineMessageSender();
    }

    private synchronized void checkRunningOnlineMessageSender() {
        if(this.onlineMessageSenderThread == null
                && this.onlineMessageSources != null && this.onlineMessageSources.size() > 0) {
            this.onlineMessageSenderThread = new OnlineMessageSenderThread();
            this.onlineMessageSenderThread.start();
        }
    }


    public void run() {
        ASAP_1_0 protocol = new ASAP_Modem_Impl();

        // introduce yourself
        CharSequence owner = this.multiASAPEngineFS.getOwner();
        if(owner != null && owner.length() > 0) {
            try {
                System.out.println(this.getLogStart() + "send introduction ASAP Offer; owner: " + owner);
                this.sendIntroductionOffer(owner, false);
            } catch (IOException e) {
                this.terminate("io error when sending introduction offering: ", e);
                return;
            } catch (ASAPException e) {
                System.out.println(this.getLogStart()
                        + "could not send introduction offer: " + e.getLocalizedMessage());
                // go ahead - no io problem
            }
        }

        try {
            // let engine write their interest
            this.multiASAPEngineFS.pushInterests(this.os);
        } catch (IOException | ASAPException e) {
            this.terminate("error when pushing interest: ", e);
            return;
        }

        /////////////////////////////// read
        while (!this.terminated) {
            this.pduReader = new ASAPPDUReader(protocol, is, this);
            try {
                System.out.println(this.startLog() + "start reading");
                this.runObservedThread(pduReader, this.maxExecutionTime);
            } catch (ASAPExecTimeExceededException e) {
                System.out.println(this.startLog() + "reading on stream took longer than allowed");
            }

            System.out.println(this.getLogStart() + "back from reading");
            if(terminated) break; // could be killed in the meantime

            if (pduReader.getIoException() != null || pduReader.getAsapException() != null) {
                Exception e = pduReader.getIoException() != null ?
                        pduReader.getIoException() : pduReader.getAsapException();

                this.terminate("exception when reading from stream (stop asap session): ", e);
                break;
            }

            ASAP_PDU_1_0 asappdu = pduReader.getASAPPDU();
            /////////////////////////////// process
            if(asappdu != null) {
                System.out.println(this.getLogStart() + "read valid pdu");
                this.setRemotePeer(asappdu.getPeer());
                // process received pdu
                try {
                    this.executor =
                            this.multiASAPEngineFS.getExecutorThread(asappdu, this.is, this.os, this);
                    // get exclusive access to streams
                    System.out.println(this.startLog() + "asap pdu executor going to wait for stream access");
                    this.wait4ExclusiveStreamsAccess();
                    try {
                        System.out.println(this.startLog() + "asap pdu executor got stream access - process pdu");
                        this.runObservedThread(executor, maxExecutionTime);
                    } catch (ASAPExecTimeExceededException e) {
                        System.out.println(this.startLog() + "asap pdu processing took longer than allowed");
                        this.terminate("asap pdu processing took longer than allowed", e);
                        break;
                    } finally {
                        // wake waiting thread if any
                        this.releaseStreamsLock();
                        System.out.println(this.startLog() + "asap pdu executor release locks");
                    }
                }  catch (ASAPException e) {
                    System.out.println(this.getLogStart() + " problem when executing asap received pdu: " + e);
                }
            }
        }
    }

    private Thread threadUsingStreams = null;
    private synchronized Thread getThreadUsingStreams(Thread t) {
        if(this.threadUsingStreams == null) {
            this.threadUsingStreams = t;
            return null;
        }

        return this.threadUsingStreams;
    }

    private void wait4ExclusiveStreamsAccess() {
        // synchronize with other thread using streams
        Thread threadUsingStreams = this.getThreadUsingStreams(Thread.currentThread());

        // got lock - go ahead
        if(threadUsingStreams == null) {
            return;
        }

        // there is another stream - wait until it dies
        do {
            System.out.println(this.getLogStart() + "enter waiting loop for exclusive stream access");
            // wait
            try {
                this.threadWaiting4StreamsLock = Thread.currentThread();
                threadUsingStreams.join();
            } catch (InterruptedException e) {
                System.out.println(this.getLogStart() + "woke up from join");
            }
            finally {
                this.threadWaiting4StreamsLock = null;
            }
            // try again
            System.out.println(this.getLogStart() + "try to get streams access again");
            threadUsingStreams = this.getThreadUsingStreams(Thread.currentThread());
        } while(threadUsingStreams != null);
        System.out.println(this.getLogStart() + "leave waiting loop for exclusive stream access");
    }

    private void releaseStreamsLock() {
        this.threadUsingStreams = null; // take me out
        if(this.threadWaiting4StreamsLock != null) {
            System.out.println(this.getLogStart() + "wake waiting thread");
            this.threadWaiting4StreamsLock.interrupt();
        }
    }

    private void runObservedThread(Thread t, long maxExecutionTime) throws ASAPExecTimeExceededException {
        this.managementThread = Thread.currentThread();
        t.start();

        // wait for reader
        try {
            Thread.sleep(maxExecutionTime);
        } catch (InterruptedException e) {
            // was woken up by thread - that's good
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("thread (");
        sb.append(t.getClass().getSimpleName());
        sb.append(") exceeded max execution time of ");
        sb.append(maxExecutionTime);
        sb.append(" ms");

        throw new ASAPExecTimeExceededException(sb.toString());
    }

    private StringBuilder startLog() {
        StringBuilder sb = net.sharksystem.asap.util.Log.startLog(this);
        sb.append(" recipient: ");
        sb.append(this.remotePeer);
        sb.append(" | ");

        return sb;
    }
}

