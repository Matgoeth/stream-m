/*
 * Copyright 2018 Bence Varga
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.czentral.minirtmp;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author bence
 */
public class ApplicationContext implements ChunkProcessor {
    
    protected boolean alive = true;
    
    protected Map<Integer, AssemblyBuffer> assemblyBuffers = new HashMap<>();
    
    protected OutputStream outputStream;
    
    protected ResourceLimit limit;
    
    protected int fcpublishTxID = 0;
    
    protected ApplicationLibrary library;
    
    protected ApplicationInstance applicationInstance;
    
    protected String clientId;

    private static final Logger LOG = Logger.getLogger(ApplicationContext.class.getName());
    
    public ApplicationContext(OutputStream outputStream, ResourceLimit limit, ApplicationLibrary library, String clientId) {
        this.outputStream = outputStream;
        this.limit = limit;
        this.library = library;
        this.clientId = clientId;
    }

    public ApplicationContext(OutputStream outputStream, ResourceLimit limit, ApplicationLibrary factory) {
        this(outputStream, limit, factory, UUID.randomUUID().toString());
    }

    /**
     * Get the value of outputStream
     *
     * @return the value of outputStream
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * Set the value of outputStream
     *
     * @param outputStream new value of outputStream
     */
    public void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    /**
     * Get the value of limit
     *
     * @return the value of limit
     */
    public ResourceLimit getLimit() {
        return limit;
    }

    /**
     * Set the value of limit
     *
     * @param limit new value of limit
     */
    public void setLimit(ResourceLimit limit) {
        this.limit = limit;
    }

    
    @Override
    public void processChunk(MessageInfo mi, byte[] readBuffer, int payloadOffset, int payloadLength) {
        processChunk(mi, ByteBuffer.wrap(readBuffer, payloadOffset, payloadLength));
    }

    public void processChunk(MessageInfo mi, ByteBuffer buffer) {

        int payloadLength = buffer.remaining();
        boolean fragmented = (mi.length > buffer.remaining());
        if (fragmented) {
            AssemblyBuffer assemblyBuffer = assemblyBuffers.get(mi.chunkStreamID);
            
            boolean newFragment = (mi.offset == 0);
            if (newFragment) {
                
                if (assemblyBuffer != null) {
                    throw new RuntimeException("Trying so start new fragment whith an other one in progress.");
                }
                
                if (mi.length > limit.assemblyBufferSize) {
                    throw new RuntimeException("Resource limit reached: message too large (" + mi.length + " > " + limit.assemblyBufferSize + ").");
                }
                
                if (assemblyBuffers.size() >= limit.assemblyBufferCount) {
                    throw new RuntimeException("Resource limit reached: too many messages to assemble.");
                }
                
                assemblyBuffer = new AssemblyBuffer(mi.length);
                assemblyBuffers.put(mi.chunkStreamID, assemblyBuffer);
            }

            buffer.get(assemblyBuffer.array, mi.offset, buffer.remaining());
            //System.arraycopy(readBuffer, payloadOffset, assemblyBuffer.array, mi.messageOffset, payloadLength);

            boolean assembled = (mi.offset + payloadLength >= mi.length);
            if (assembled) {
                processMessage(mi, assemblyBuffer.array, 0, mi.length);
                assemblyBuffers.put(mi.chunkStreamID, null);
            }
            
        } else {
            byte[] resultBuffer = new byte[buffer.remaining()];
            buffer.get(resultBuffer, 0, buffer.remaining());
            //System.arraycopy(readBuffer, payloadOffset, resultBuffer, 0, payloadLength);
            processMessage(mi, resultBuffer, 0, resultBuffer.length);
        }
        //System.err.print(HexDump.prettyPrintHex(buffer, payloadOffset, Math.min(16, payloadLength)));
    }
    
    protected void processMessage(MessageInfo mi, byte[] buffer, int payloadOffset, int payloadLength) {
        //System.err.print(HexDump.prettyPrintHex(buffer, payloadOffset, payloadLength));
        
        int type = mi.type;
        AMFDecoder ac = new AMFDecoder(buffer, payloadOffset, payloadLength);
        
        try {
            if (type == 0x14) {
                RTMPCommand command = readCommand(ac);

                if (applicationInstance == null) {

                    if (command.getName().equals("connect")) {

                        String appName = (String)command.getCommandObject().get("app");
                        try {
                            Application app = library.getApplication(appName);
                            applicationInstance = app.getInstance();
                        } catch (ApplicationNotFoundException e) {
                            AMFPacket response = new AMFPacket();
                            response.writeString("_error");
                            response.writeMixed(null);
                            response.writeMixed(null);
                            writeCommand(mi.chunkStreamID, mi.messageStreamID, response);
                            return;
                        }
                        applicationInstance.onConnect(this);

                        applicationInstance.invokeCommand(mi, command);

                    } else {

                        AMFPacket response = new AMFPacket();
                        response.writeString("_error");
                        response.writeMixed(null);
                        response.writeMixed(null);
                        writeCommand(mi.chunkStreamID, mi.messageStreamID, response);
                    }
                } else {
                    applicationInstance.invokeCommand(mi, command);
                }

            } else if (type == 0x12) {
                RTMPCommand command = readData(ac);
                applicationInstance.onData(mi, command);
            } else if (type == 0x08 || type == 0x09) {
                applicationInstance.mediaChunk(mi, buffer, payloadOffset, payloadLength);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, clientId, ex);
            
            AMFPacket response = new AMFPacket();
            response.writeString("_error");
            response.writeMixed(null);
            response.writeMixed(null);
            writeCommand(mi.chunkStreamID, mi.messageStreamID, response);
            
            throw new RuntimeException(ex);
        }
        
    }
    
    protected RTMPCommand readCommand(AMFDecoder decoder) {
        String commandName = decoder.readString();
        double txid = decoder.readNumber();
        Map<String, Object> commandObject = (Map<String, Object>)decoder.readMixed();

        List<Object> arguments = new LinkedList<Object>();
        while (!decoder.eof()) {
            Object argument = decoder.readMixed();
            arguments.add(argument);
        }
        
        return new RTMPCommand(commandName, txid, commandObject, arguments);
    }
    
    protected RTMPCommand readData(AMFDecoder decoder) {
        List<Object> arguments = new LinkedList<Object>();
        while (!decoder.eof()) {
            Object argument = decoder.readMixed();
            arguments.add(argument);
        }
        
        return new RTMPCommand("", 0d, null, arguments);
    }
    
    public void writeCommand(int streamID, int messageStreamID, AMFPacket message) {
        byte[] header = new byte[12];
        header[0] = (byte)((streamID) & 0x3f);

        // length
        int responseLength = message.getLength();
        header[4] = (byte) ((responseLength >> 16) & 0xff);
        header[5] = (byte) ((responseLength >> 8) & 0xff);
        header[6] = (byte) ((responseLength) & 0xff);

        // message type
        header[7] = 0x14;

        header[8] = (byte) ((messageStreamID >> 24) & 0xff);
        header[9] = (byte) ((messageStreamID >> 16) & 0xff);
        header[10] = (byte) ((messageStreamID >> 8) & 0xff);
        header[11] = (byte) ((messageStreamID) & 0xff);

        // writing
        try {
            outputStream.write(header);
            outputStream.write(message.getBuffer(), 0, responseLength);
            outputStream.flush();
            //System.out.write(header);
            //System.out.write(response.getBuffer(), 0, responseLength);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private static class AssemblyBuffer {
        public static byte[] array;

        public AssemblyBuffer(int size) {
            array = new byte[size];
        }
    }
    
    
    @Override
    public boolean alive() {
        return alive;
    }
    
    public void terminate() {
        alive = false;
    }
    
}
