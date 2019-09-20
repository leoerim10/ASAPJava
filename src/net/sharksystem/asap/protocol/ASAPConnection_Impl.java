package net.sharksystem.asap.protocol;

import net.sharksystem.asap.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ASAPConnection_Impl implements ASAPConnection, Runnable, ThreadFinishedListener {
    private final InputStream is;
    private final OutputStream os;
    private final ASAPConnectionListener asapConnectionListener;
    private final MultiASAPEngineFS multiASAPEngineFS;
    private final ThreadFinishedListener threadFinishedListener;
    private final ASAP_1_0 protocol;
    private Thread managementThread = null;
    private final long maxExecutionTime;
    private String peer;

    private List<byte[]> onlineMessageList = new ArrayList<>();
    private List<ASAPOnlineMessageSource> onlineMessageSources = new ArrayList<>();

    public ASAPConnection_Impl(InputStream is, OutputStream os, MultiASAPEngineFS multiASAPEngineFS,
                               ASAP_1_0 protocol,
                               long maxExecutionTime, ASAPConnectionListener asapConnectionListener,
                               ThreadFinishedListener threadFinishedListener) {
        this.is = is;
        this.os = os;
        this.multiASAPEngineFS = multiASAPEngineFS;
        this.protocol = protocol;
        this.maxExecutionTime = maxExecutionTime;
        this.asapConnectionListener = asapConnectionListener;
        this.threadFinishedListener = threadFinishedListener;
    }

    private String getLogStart() {
        return this.getClass().getSimpleName() + ": ";
    }

    private void setPeer(String peerName) {
        if(this.peer == null) {

            this.peer = peerName;

            StringBuilder sb = new StringBuilder();
            sb.append(this.getLogStart());
            sb.append("set peerName after reading first asap message: ");
            sb.append(peerName);
            System.out.println(sb.toString());

            if(this.asapConnectionListener != null) {
                this.asapConnectionListener.asapConnectionStarted(peerName, this);
            }
        }
    }

    private synchronized void addOnlineMessage(byte[] message) {
        this.onlineMessageList.add(message);
    }

    private synchronized byte[] remomveOnlineMessage() {
        return this.onlineMessageList.remove(0);
    }

    @Override
    public CharSequence getRemotePeer() {
        return this.peer;
    }

    @Override
    public void addOnlineMessageSource(ASAPOnlineMessageSource source) {
        this.onlineMessageSources.add(source);
    }

    @Override
    public void removeOnlineMessageSource(ASAPOnlineMessageSource source) {
        this.onlineMessageSources.remove(source);
    }

    public boolean isSigned() {
        return false;
    }

    @Override
    public void finished(Thread t) {
        if(this.managementThread != null) {
            this.managementThread.interrupt();
        }
    }

    private void terminate(String message) {
        this.terminate(message, null);
    }

    private void terminate(String message, Exception e) {
        // write log
        StringBuilder sb = this.startLog();
        sb.append(message);
        if(e != null) {
            sb.append(e.getLocalizedMessage());
        }

        sb.append(" | ");

        /*
        try {
            this.os.close();
            this.is.close();
            sb.append("closed streams");
            System.out.println(sb.toString());
        }
        catch (IOException ioe) {
            sb.append("could not close streams: ");
            sb.append(ioe.getLocalizedMessage());
            System.err.println(sb.toString());
        }
         */

        // inform listener
        if(this.asapConnectionListener != null) {
            this.asapConnectionListener.asapConnectionTerminated(e, this);
        }

        if(this.threadFinishedListener != null) {
            this.threadFinishedListener.finished(this.managementThread);
        }
    }

    private void sendOnlineMessages() throws IOException {
        while(!this.onlineMessageSources.isEmpty()) {
            ASAPOnlineMessageSource asapOnline = this.onlineMessageSources.get(0);
            StringBuilder sb = this.startLog();
            sb.append("going to send online message");
            System.out.println(sb.toString());
            asapOnline.sendMessages(this, this.os);
        }
    }

    public void run() {
        ASAP_1_0 protocol = new ASAP_Modem_Impl();

        try {
            // let engine write their interest

            this.multiASAPEngineFS.pushInterests(this.os);
        } catch (IOException | ASAPException e) {
            this.terminate("error when pushing interest: ", e);
            return;
        }

        // start reading / processing loop
        while (true) {
            // read asap pdu
            ASAPPDUReader pduReader = new ASAPPDUReader(protocol, is, this);
            try {
                this.runObservedThread(pduReader, this.maxExecutionTime);
            } catch (ASAPExecTimeExceededException e) {
                System.out.println(this.startLog() + "reading on stream took longer than allowed");
            }

            // exception caught while reading?
            if (pduReader.getIoException() != null || pduReader.getAsapException() != null) {
                Exception e = pduReader.getIoException() != null ?
                        pduReader.getIoException() : pduReader.getAsapException();

                this.terminate("exception when reading from stream (stop asap session): ", e);
                return;
            }

            ASAP_PDU_1_0 asappdu = pduReader.getASAPPDU();

            // we have read something - remember peer
            if(asappdu != null) {
                this.setPeer(asappdu.getPeer());
                // process received pdu
                try {
                    Thread executor =
                            this.multiASAPEngineFS.getExecutorThread(asappdu, this.is, this.os, this);
                    this.runObservedThread(executor, this.maxExecutionTime);
                } catch (ASAPExecTimeExceededException e) {
                    System.out.println(this.startLog() + "asap pdu processing took longer than allowed");
                } catch (ASAPException e) {
                    this.terminate("serious problem when executing asap received pdu: ", e);
                    return;
                }
            }

            // pdu processed - we have a free channel now - use it to send online messages - if any
            try {
                this.sendOnlineMessages();
            } catch (IOException e) {
                this.terminate("exception when sending online messages: " + e.getLocalizedMessage());
                return;
            }
            // next loop
        }
    }

    private Thread thread2wait4;
    private void runObservedThread(Thread t, long maxExecutionTime) throws ASAPExecTimeExceededException {
        this.thread2wait4 = t;
        t.start();

        // wait for reader
        try {
            this.managementThread = Thread.currentThread();
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
        sb.append(this.peer);
        sb.append(" | ");

        return sb;
    }
}

