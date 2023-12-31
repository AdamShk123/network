/*
    WUMP (specifically HUMP) in java. starter file
 */
import java.lang.*;     //pld
import java.net.*;      //pld
import java.io.*;
//import wumppkt.*;         // be sure wumppkt.java is in your current directory
import java.io.Externalizable;

// As is, this packet should receive data[1] and time out.
// If you send the ACK to the correct port, you should receive data[2]
// If you update expected_block, you should receive the entire file, for "vanilla"
// If you write the sanity checks, you should receive the entire file in all cases

public class wclient {

    //============================================================
    //============================================================

    static public void main(String args[])
    {
        int srcport;
        int destport = wumppkt.SERVERPORT;
	    //destport = wumppkt.SAMEPORT;		// 4716; server responds from same port
        String filename = "vanilla";
        String desthost = "ulam.cs.luc.edu";
        int winsize = 1;
	    int latchport = 0;
	    short THEPROTO = wumppkt.HUMPPROTO;
	    wumppkt.setproto(THEPROTO);

        if (args.length > 0) filename = args[0];
        if (args.length > 1) winsize = Integer.parseInt(args[1]);
        if (args.length > 2) desthost = args[2];

        DatagramSocket s;
        try {
            s = new DatagramSocket();
        }
        catch (SocketException se) {
            System.err.println("no socket available");
            return;
        }

        try {
            s.setSoTimeout(wumppkt.INITTIMEOUT);       // time in milliseconds
        } catch (SocketException se) {
            System.err.println("socket exception: timeout not set!");
        }

       if (args.length > 3)
       {
            System.err.println("usage: wclient filename  [winsize [hostname]]");
            //exit(1);
       }

		// DNS lookup
        InetAddress dest;
        System.err.print("Looking up address of " + desthost + "...");
        try {
            dest = InetAddress.getByName(desthost);
        }
        catch (UnknownHostException uhe) {
            System.err.println("unknown host: " + desthost);
            return;
        }
        System.err.println(" got it!");

		// build REQ & send it
        wumppkt.REQ req = new wumppkt.REQ(winsize, filename); // ctor for REQ

        System.err.println("req size = " + req.size() + ", filename=" + req.filename());

        DatagramPacket reqDG
            = new DatagramPacket(req.write(), req.size(), dest, destport);
        try {s.send(reqDG);}
        catch (IOException ioe) {
            System.err.println("send() failed");
            return;
        }

        //============================================================

        // now get set up to receive responses
        DatagramPacket replyDG            // we don't set the address here!
            = new DatagramPacket(new byte[wumppkt.MAXSIZE] , wumppkt.MAXSIZE);
        DatagramPacket ackDG = new DatagramPacket(new byte[0], 0);
        ackDG.setAddress(dest);
        //ackDG.setPort(destport);	// this is wrong for wumppkt.SERVERPORT version

        int expected_block = 1;
        long starttime = System.currentTimeMillis();
        long sendtime = starttime;

        wumppkt.DATA  data  = null;
        wumppkt.ERROR error = null;
        wumppkt.ACK   ack   = null;

        int proto;        // for proto of incoming packets
        int opcode;
        int length;
	    int blocknum;

        //====== HUMP =====================================================

	    //Get HANDOFF, send ACK[0]
        
        try {
                s.receive(replyDG);
        }
        catch (SocketTimeoutException ste) {
		    System.err.println("hard timeout waiting for HANDOFF");
		    // what do you do here??
            try {s.send(reqDG);}
            catch (IOException ioe) {
                System.err.println("send() failed");
                return;
            }

            try {
                s.receive(replyDG);
            }
            catch (SocketTimeoutException stea) {
                System.err.println("hard timeout waiting for HANDOFF");
                return;
            }
            catch (IOException ioea) {
                System.err.println("receive() failed");
                return;
            }
        }
        catch (IOException ioe) {
                System.err.println("receive() failed");
                return;
        }
            
        byte[] replybuf = replyDG.getData();
        proto   = wumppkt.proto(replybuf);
        opcode  = wumppkt.opcode(replybuf);
        length  = replyDG.getLength();
        srcport = replyDG.getPort();

        // HANDOFF sanity checks

        if (proto != THEPROTO) {
            System.err.println("reply is not a HUMP protocol");
            return;
        }
        if (opcode != wumppkt.HANDOFFop) {
            System.err.println("incoming packet is not a HANDOFF");
            return;
        }
        if (srcport != destport) {
            System.err.println("received handoff from wrong port");
            return;
        }

        // construct the HANDOFF object from the incoming packet
        wumppkt.HANDOFF handoff = new wumppkt.HANDOFF(replybuf);
        int newport = handoff.newport();

        System.err.println("handoff received; new port is " + newport);

	    // send ack[0] to new port
        ack = new wumppkt.ACK(0);		// what should it be? wumppkt.ACK(0);
        ackDG.setData(ack.write());
        ackDG.setLength(ack.size());
	    ackDG.setPort(newport);	// what port?

	    // now send ackDG, using s.send(), as above
        try {
            s.send(ackDG);
        }
        catch (IOException e) {
            System.err.println(e);
        }

        //====== MAIN LOOP ================================================
	    // now you wait for each DATA[n], and send ACK[n] in reply
	    // All DATA[n] must come from newport, above.

        boolean dally = false;
        int timeout_counter = 0;

        while (true) {
            // get packet
            try {
                s.receive(replyDG);
            }
            catch (SocketTimeoutException ste) {
				System.err.println("hard timeout");
				// what do you do here??; retransmit of previous packet here
                if(!dally) {
                    long curr = System.currentTimeMillis();
                    if(curr - sendtime > 2000) {
                        System.err.println("Timeout over 2 seconds. Retransmitting ack...");
                        try {
                            s.send(ackDG);
                        }
                        catch (IOException ioe) {
                            System.err.println("send() failed");
                            return;
                        }
                        sendtime = curr;
                    }
                }
                else {
                    timeout_counter++;

                    if(timeout_counter == 3) {
                        System.err.println("DALLY over. Exiting...");
                        return;
                    }
                }
                continue;
            }
            catch (IOException ioe) {
                System.err.println("receive() failed");
                return;
            }

            replybuf = replyDG.getData();
            proto   = wumppkt.proto(replybuf);
            opcode  = wumppkt.opcode(replybuf);
            length  = replyDG.getLength();
            srcport = replyDG.getPort();

            /* The new packet might not actually be a DATA packet.
             * But we can still build one and see, provided:
             *   1. proto =   THEPROTO
             *   2. opcode =  wumppkt.DATAop
             *   3. length >= wumppkt.DHEADERSIZE
             */

            data = null; error = null;
            blocknum = 0;
            if (  proto == THEPROTO && opcode == wumppkt.DATAop && length >= wumppkt.DHEADERSIZE) {
                data = new wumppkt.DATA(replybuf, length);
                blocknum = data.blocknum();
            } else if ( proto == THEPROTO && opcode == wumppkt.ERRORop && length >= wumppkt.EHEADERSIZE) {
                error = new wumppkt.ERROR(replybuf);
            }

            printInfo(replyDG, data, starttime);

            if(!dally) {
                // now check the packet for appropriateness
                // if it passes all the checks:
                //write data, increment expected_block
                // exit if data size is < 512

                if (error != null) {
                    System.err.println("Error packet rec'd; code " + error.errcode());
                    continue;
                }
                if (data == null)
                    continue;		// typical error check, but you should

                // The following is for you to do:
                // check port, packet size, type, block, etc
                // latch on to port, if block == 1

                if(data.blocknum() == 1 && latchport == 0) {
                    latchport = newport;
                    System.err.println("DATA[1] received. Latching on to port");
                    System.err.println("UNLATCHED -> ESTABLISHED");
                }

                if(srcport != latchport) {
                    System.err.println("source port {" + srcport + "} and destination port {" + latchport+ "} aren't the same!");
                    System.err.println("sending error packet");
                    error = new wumppkt.ERROR(wumppkt.EBADPORT);
                    DatagramPacket errorDG = new DatagramPacket(error.write(), error.size());
                    errorDG.setPort(latchport);
                    errorDG.setAddress(dest);
                    try {
                        s.send(errorDG);
                    }
                    catch (IOException e) {
                        System.err.println("Failed to send error packet!");
                    }
                    sendtime = System.currentTimeMillis();
                    continue;
                }

                if(!replyDG.getAddress().getHostAddress().equals(dest.getHostAddress())) {
                    System.err.println("IP Addresses don't match! replyIP:" + replyDG.getAddress().getHostAddress() + " myIP: " + dest.getHostAddress());
                    continue;
                }

                if(proto != wumppkt.HUMPPROTO) {
                    System.err.println("protocol is incorrect!");
                    continue;
                }

                if(opcode != wumppkt.DATAop) {
                    System.err.println("opcode isn't data!");
                    continue;
                }

                if(data.blocknum() != expected_block) {
                    System.err.println("Blocks don't match");
                    long curr = System.currentTimeMillis();
                    if(curr - sendtime > 2000) {
                        System.err.println("Timeout over 2 seconds. Retransmitting ack...");
                        try {
                            s.send(ackDG);
                        }
                        catch (IOException ioe) {
                            System.err.println("send() failed");
                            return;
                        }
                        sendtime = curr;
                    }
                    continue;
                }

                // write data
                System.out.write(data.bytes(), 0, data.size() - wumppkt.DHEADERSIZE);

                // send ack
                ack = new wumppkt.ACK(expected_block);
                ackDG.setData(ack.write());
                ackDG.setLength(ack.size());
                ackDG.setPort(latchport);

                if(data.size() < 512) {
                    System.err.println("Size of packet is smaller than 512 bytes. Exiting...");
                    System.err.println("ESTABLISHED -> DALLY");
                    expected_block--;
                    dally = true;
                }

                try {
                    s.send(ackDG);
                    expected_block++;
                }
                catch (IOException ioe) {
                    System.err.println("send() failed");
                    return;
                }

                sendtime = System.currentTimeMillis();
            }
            else {
                try {
                    System.err.println("received Data[" + expected_block + "]. sending final ACK[" + expected_block + "]");
                    s.send(ackDG);
                }
                catch (IOException ioe) {
                    System.err.println("send() failed");
                    return;
                }
            }
        } // while
    }

    // print packet length, protocol, opcode, source address/port, time, blocknum
    static public void printInfo(DatagramPacket pkt, wumppkt.DATA data, long starttime) {
        byte[] replybuf = pkt.getData();
        int proto = wumppkt.proto(replybuf);
        int opcode = wumppkt.opcode(replybuf);
        int length = replybuf.length;
	// the following seven items we can print always
        System.err.print("rec'd packet: len=" + length);
        System.err.print("; proto=" + proto);
        System.err.print("; opcode=" + opcode);
        System.err.print("; src=(" + pkt.getAddress().getHostAddress() + "/" + pkt.getPort()+ ")");
        System.err.print("; time=" + (System.currentTimeMillis()-starttime));
        System.err.println();
        if (data==null)
            System.err.println("         packet does not seem to be a data packet");
        else
            System.err.println("         DATA packet blocknum = " + data.blocknum());
    }

    // extracts blocknum from raw packet
    // blocknum is laid out in big-endian order in b[4]..b[7]
    static public int getblock(byte[] buf) {
        //if (b.length < 8) throw new IOException("buffer too short");
        return  (((buf[4] & 0xff) << 24) |
		 ((buf[5] & 0xff) << 16) |
		 ((buf[6] & 0xff) <<  8) |
		 ((buf[7] & 0xff)      ) );
    }


}
