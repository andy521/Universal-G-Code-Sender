/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.willwinder.universalgcodesender;

import gnu.io.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.LinkedList;
import java.util.TooManyListenersException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Timer;

/**
 *
 * @author wwinder
 */
public class SerialCommunicator implements SerialPortEventListener{
    private double grblVersion;         // The 0.8 in 'Grbl 0.8c'
    private String grblVersionLetter;   // The c in 'Grbl 0.8c'
    
    private Boolean realTimeMode = false;
    private Boolean realTimePosition = false;
    private CommUtils.Capabilities positionMode = null;
    
    // General variables
    private CommPort commPort;
    private InputStream in;
    private OutputStream out;
    private String lineTerminator = "\r\n";
    private boolean isPositionPending = false;
    private Timer positionPollTimer = null;    
    // File transfer variables.
    private Boolean sendPaused = false;
    private boolean fileMode = false;
    private File file = null;
    private GcodeCommandBuffer commandBuffer;   // All commands in a file
    private LinkedList<GcodeCommand> activeCommandList;  // Currently running commands

    // Callback interfaces
    SerialCommunicatorListener fileStreamCompleteListener;
    SerialCommunicatorListener commandQueuedListener;
    SerialCommunicatorListener commandSentListener;
    SerialCommunicatorListener commandCompleteListener;
    SerialCommunicatorListener commandPreprocessorListener;
    SerialCommunicatorListener commConsoleListener;
    SerialCommunicatorListener commVerboseConsoleListener;
    SerialCommunicatorListener capabilitiesListener;
    SerialCommunicatorListener positionListener;
    
    /** Getters & Setters. */
    void setLineTerminator(String terminator) {
        if (terminator.length() < 1) {
            this.lineTerminator = "\r\n";
        } else {
            this.lineTerminator = terminator;
        }
    }

    // Register for callbacks
    void setListenAll(SerialCommunicatorListener fscl) {
        this.setFileStreamCompleteListener(fscl);
        this.setCommandQueuedListener(fscl);
        this.setCommandSentListener(fscl);
        this.setCommandCompleteListener(fscl);
        this.setCommandPreprocessorListener(fscl);
        this.setCommConsoleListener(fscl);
        this.setCommVerboseConsoleListener(fscl);
        this.setCapabilitiesListener(fscl);
        this.setPositionStringListener(fscl);
    }
    
    void setFileStreamCompleteListener(SerialCommunicatorListener fscl) {
        this.fileStreamCompleteListener = fscl;
    }
    
    void setCommandQueuedListener(SerialCommunicatorListener fscl) {
        this.commandQueuedListener = fscl;
    }
    
    void setCommandSentListener(SerialCommunicatorListener fscl) {
        this.commandSentListener = fscl;
    }
    
    void setCommandCompleteListener(SerialCommunicatorListener fscl) {
        this.commandCompleteListener = fscl;
    }
    
    void setCommandPreprocessorListener(SerialCommunicatorListener fscl) {
        this.commandPreprocessorListener = fscl;
    }
    
    void setCommConsoleListener(SerialCommunicatorListener fscl) {
        this.commConsoleListener = fscl;
    }

    void setCommVerboseConsoleListener(SerialCommunicatorListener fscl) {
        this.commVerboseConsoleListener = fscl;
    }
    
    void setCapabilitiesListener(SerialCommunicatorListener fscl) {
        this.capabilitiesListener = fscl;
    }
    
    void setPositionStringListener(SerialCommunicatorListener fscl) {
        this.positionListener = fscl;
    }

    
    // Must create /var/lock on OSX, fixed in more current RXTX (supposidly):
    // $ sudo mkdir /var/lock
    // $ sudo chmod 777 /var/lock
    synchronized boolean openCommPort(String name, int baud) 
            throws NoSuchPortException, PortInUseException, 
            UnsupportedCommOperationException, IOException, 
            TooManyListenersException {
        
        this.commandBuffer = new GcodeCommandBuffer();
        this.activeCommandList = new LinkedList<GcodeCommand>();

        boolean returnCode;

        CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(name);
           
        if (portIdentifier.isCurrentlyOwned()) {
            returnCode = false;
        } else {
                this.commPort = portIdentifier.open(this.getClass().getName(), 2000);

                SerialPort serialPort = (SerialPort) this.commPort;
                serialPort.setSerialPortParams(baud,SerialPort.DATABITS_8,SerialPort.STOPBITS_1,SerialPort.PARITY_NONE);

                this.in = serialPort.getInputStream();
                this.out = serialPort.getOutputStream();

                serialPort.addEventListener(this);
                serialPort.notifyOnDataAvailable(true);  
                serialPort.notifyOnBreakInterrupt(true);

                this.sendMessageToConsoleListener("**** Connected to " 
                                        + name
                                        + " @ "
                                        + baud
                                        + " baud ****\n");

                returnCode = true;
        }

        return returnCode;
    }
        
    void closeCommPort() {
        //This is unnecessary and slow
        //this.cancelSend();

        try {
            in.close();
            out.close();
        } catch (IOException ex) {
            Logger.getLogger(SerialCommunicator.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        SerialPort serialPort = (SerialPort) this.commPort;
        serialPort.removeEventListener();
        this.commPort.close();

        this.sendMessageToConsoleListener("**** Connection closed ****\n");
    }
    
    void queueStringForComm(String commandString) {
        // Add command to queue
        GcodeCommand command = this.commandBuffer.appendCommandString(commandString);

        if (this.commandQueuedListener != null) {
            this.commandQueuedListener.commandQueued(command);
        }

        // Send command to the serial port.
        this.streamCommands();
    }
    
    /**
     * Sends a command to the serial device.
     * @param command   Command to be sent to serial device.
     */
    void sendStringToComm(String command) {
        // Command already has a newline attached.
        this.sendMessageToConsoleListener(">>> " + command);
        
         // Send command to the serial port.
         PrintStream printStream = new PrintStream(this.out);
         printStream.print(command);
         printStream.close();     
    }
    
    /**
     * Immediately sends a byte, used for real-time commands.
     */
    void sendByteImmediately(byte b) throws IOException {
        out.write(b);
    }
       
    /*
    // TODO: Figure out why this isn't working ...
    boolean isCommPortOpen() throws NoSuchPortException {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(this.commPort.getName());
            String owner = portIdentifier.getCurrentOwner();
            String thisClass = this.getClass().getName();
            
            return portIdentifier.isCurrentlyOwned() && owner.equals(thisClass);                    
    }
    */
    
    /** File Stream Methods. **/
    
    void isReadyToStreamFile() throws Exception {
        if (this.fileMode == true) {
            throw new Exception("Already sending a file.");
        }
        if ((this.commandBuffer.size() > 0) && 
                (this.commandBuffer.currentCommand().isDone() != true)) {
            throw new Exception("Cannot send file until there are no commands running. (Commands remaining in command buffer)");
        }
        if (this.activeCommandList.size() > 0) {
            throw new Exception("Cannot send file until there are no commands running. (Active Command List > 0).");
        }

    }
    
    // Setup for streaming to serial port then launch the first command.
    void streamFileToComm(File commandfile) throws Exception {
        isReadyToStreamFile();
        
        this.fileMode = true;

        // Get command list.
        try {
            this.file = commandfile;
            
            FileInputStream fstream = new FileInputStream(this.file);
            DataInputStream dis = new DataInputStream(fstream);
            BufferedReader fileStream = new BufferedReader(new InputStreamReader(dis));

            String line;
            GcodeCommand command;
            while ((line = fileStream.readLine()) != null) {
                // Add command to queue
                command = this.commandBuffer.appendCommandString(line + '\n');
                
                // Notify listener of new command
                if (this.commandQueuedListener != null) {
                    this.commandQueuedListener.commandQueued(command);
                }
            }
        } catch (Exception e) {
            // On error, wrap up then re-throw exception for GUI to display.
            finishStreamFileToComm();
            throw e;
        }
        
        // Start sending commands.
        this.streamCommands();     
    }
    
    void streamCommands() {
        boolean skip;
        
        // The GcodeCommandBuffer class always preloads the next command, so as
        // long as the currentCommand exists and hasn't been sent it is the next
        // which should be sent.
        
        while ((this.commandBuffer.currentCommand().isSent() == false) &&
                CommUtils.checkRoomInBuffer(this.activeCommandList, this.commandBuffer.currentCommand())) {

            skip = false;
            GcodeCommand command = this.commandBuffer.currentCommand();

            // Allow a command preprocessor listener to preprocess the command.
            if (this.commandPreprocessorListener != null) {
                String processed = this.commandPreprocessorListener.preprocessCommand(command.getCommandString());
                command.setCommand(processed);
                
                if (processed.trim().equals("")) {
                    skip = true;
                }
            }
            
            // Don't send skipped commands.
            if (!skip) {
                command.setSent(true);

                this.activeCommandList.add(command);
            
                // Commands parsed by the buffer list have embedded newlines.
                this.sendStringToComm(command.getCommandString());
            }
            
            if (this.commandSentListener != null) {
                this.commandCompleteListener.commandSent(command);
            }
            
            // If the command was skipped let the listeners know.
            if (skip) {
                this.sendMessageToConsoleListener("Skipping command #"
                        + command.getCommandNumber() + "\n");
                command.setResponse("<skipped by application>");

                if (this.commandCompleteListener != null) {
                    this.commandCompleteListener.commandComplete(command);
                }
            }

            // Load the next command.
            this.commandBuffer.nextCommand();
        }

        // If the final response is done and we're in file mode then wrap up.
        if (this.fileMode && (this.commandBuffer.size() == 0) &&
                this.commandBuffer.currentCommand().isDone() ) {
            this.finishStreamFileToComm();
        }            
    }
    
    void finishStreamFileToComm() {
        this.fileMode = false;

        this.sendMessageToConsoleListener("\n**** Finished sending file. ****\n\n");
        // Trigger callback
        if (this.fileStreamCompleteListener != null) {
            boolean success = this.commandBuffer.currentCommand().isDone();
            this.fileStreamCompleteListener.fileStreamComplete(file.getName(), success);
        }
    }
    
    void pauseSend() throws IOException {
        this.sendMessageToConsoleListener("\n**** Pausing file transfer. ****\n");
        this.sendPaused = true;
        
        if (this.realTimeMode) {
            this.sendByteImmediately(CommUtils.GRBL_PAUSE_COMMAND);
        }
    }
    
    void resumeSend() throws IOException {
        this.sendMessageToConsoleListener("\n**** Resuming file transfer. ****\n");
                
        this.sendPaused = false;
        this.streamCommands();
        
        if (this.realTimeMode) {
            this.sendByteImmediately(CommUtils.GRBL_RESUME_COMMAND);
        }
    }
    
    void cancelSend() {
        if (this.fileMode) {
            this.sendPaused = true;
            GcodeCommand command;
            
            this.sendMessageToConsoleListener("\n**** Canceling file transfer. ****\n");


            // Cancel the current command if it isn't too late.
            command = this.commandBuffer.currentCommand();
            if (command.isSent() == false) {
                command.setResponse("<canceled by application>");                
                if (this.commandCompleteListener != null) {
                    this.commandCompleteListener.commandComplete(command);
                }
            }
            
            // Cancel the rest.
            while (this.commandBuffer.hasNext()) {
                command = this.commandBuffer.nextCommand();
                command.setResponse("<canceled by application>");
                
                if (this.commandCompleteListener != null) {
                    this.commandCompleteListener.commandComplete(command);
                }
            }
            
            this.sendPaused = false;
            
            this.finishStreamFileToComm();
        }
    }

    // Processes a serial response
    void responseMessage( String response ) {

        // Check if was 'ok' or 'error'.
        if (GcodeCommand.isOkErrorResponse(response)) {
            // All Ok/Error messages go to console
            this.sendMessageToConsoleListener(response + "\n");

            // Pop the front of the active list.
            GcodeCommand command = this.activeCommandList.pop();

            command.setResponse(response);

            if (this.commandCompleteListener != null) {
                this.commandCompleteListener.commandComplete(command);
            }

            if (this.sendPaused == false) {
                this.streamCommands();
            }
        }
        
        else if (GrblUtils.isGrblVersionString(response)) {
            // Version string goes to console
            this.sendMessageToConsoleListener(response + "\n");
            
            this.grblVersion = GrblUtils.getVersionDouble(response);
            this.grblVersionLetter = GrblUtils.getVersionLetter(response);
            
            this.realTimeMode = GrblUtils.isRealTimeCapable(this.grblVersion);
            if (this.realTimeMode) {
                if (this.capabilitiesListener != null) {
                    this.capabilitiesListener.capabilitiesListener(CommUtils.Capabilities.REAL_TIME);
                }
            }
            
            this.positionMode = GrblUtils.getGrblPositionCapabilities(this.grblVersion, this.grblVersionLetter);
            if (this.positionMode != null) {
                this.realTimePosition = true;
                
                // Start sending '?' commands.
                this.beginPollingPosition();
            }
            
            if (this.realTimePosition) {
                if (this.capabilitiesListener != null) {
                    this.capabilitiesListener.capabilitiesListener(CommUtils.Capabilities.POSITION_C);
                }
            }
            
            System.out.println("Grbl version = " + this.grblVersion + this.grblVersionLetter);
            System.out.println("Real time mode = " + this.realTimeMode);
        }
        
        else if (GrblUtils.isGrblPositionString(response)) {
            // Position string goes to verbose console
            this.sendMessageToConsoleListener(response + "\n", true);
            
            if (this.positionListener != null) {
                this.positionListener.positionStringListener(response);
            }
        }
        
        else {
            // Display any unhandled messages
            this.sendMessageToConsoleListener(response + "\n");
        }
    }
    
    private void beginPollingPosition() {
        System.out.println("BEGIN POLLING POSITION");
        
        ActionListener actionListener = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                java.awt.EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sendByteImmediately(CommUtils.GRBL_STATUS_COMMAND);
                        } catch (IOException ex) {
                            sendMessageToConsoleListener("IOException while sending status command: " + ex.getMessage());
                        }
                    }
                });
                
            }
        };
        
        this.positionPollTimer = new Timer(1000, actionListener);
        this.positionPollTimer.start();
    }

    private void stopPollingPosition() {
        if (this.positionPollTimer != null)
            this.positionPollTimer.stop();
    }
    
    @Override
    // Reads data as it is returned by the serial port.
    public void serialEvent(SerialPortEvent arg0) {

        if (arg0.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try
            {
                String next = CommUtils.readLineFromCommUntil(in, lineTerminator);
                next = next.replace("\n", "").replace("\r","");
                
                // Handle response.
                this.responseMessage(next);
                    
                /*
                 * For some reason calling readLineFromCommUntil more than once
                 * prevents the close command from working later on.
                // Read one or more lines from the comm.
                while ((next = CommUtils.readLineFromCommUntil(in, lineTerminator)).isEmpty() == false) {
                    next = next.replace("\n", "").replace("\r","");
                
                    // Handle response.
                    this.responseMessage(next);
                }
                */
            }
            catch ( IOException e )
            {
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }

    // Helper for the console listener.              
    void sendMessageToConsoleListener(String msg) {
        this.sendMessageToConsoleListener(msg, false);
    }
    
    void sendMessageToConsoleListener(String msg, boolean verbose) {
        if (!verbose && this.commConsoleListener != null) {
            this.commConsoleListener.messageForConsole(msg);
        }
        else if (verbose && this.commVerboseConsoleListener != null) {
            this.commVerboseConsoleListener.verboseMessageForConsole(msg);
        }
    }
}