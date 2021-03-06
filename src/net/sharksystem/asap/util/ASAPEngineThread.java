package net.sharksystem.asap.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.sharksystem.asap.ASAPException;
import net.sharksystem.asap.MultiASAPEngineFS;

/**
 *
 * @author thsc
 */
public class ASAPEngineThread extends Thread {
    private final MultiASAPEngineFS engine;
    private final InputStream is;
    private final OutputStream os;

    public ASAPEngineThread(MultiASAPEngineFS engine, InputStream is, OutputStream os) {
        this.engine = engine;
        this.is = is;
        this.os = os;
    }

    @Override
    public void run() {
        try {
            this.engine.handleConnection(this.is, this.os);
        } catch (IOException | ASAPException e) {
            System.err.println(this.getClass().getSimpleName() + ": " + e.getClass().getSimpleName()
                    + "caught: " + e.getLocalizedMessage());
        }
    }
}
